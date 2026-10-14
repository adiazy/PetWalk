"""CI check: the app's CLIP model files download, have the inputs/outputs the app expects,
and classify obvious test images correctly with the app's preprocessing and prompts."""
import gzip
import sys
import urllib.request
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

BASE = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/"
FILES = ["vision_model_quantized.onnx", "text_model_quantized.onnx"]
CACHE = Path(sys.argv[1] if len(sys.argv) > 1 else "clip-cache")
CACHE.mkdir(exist_ok=True)
for name in FILES:
    path = CACHE / name
    if not path.exists():
        urllib.request.urlretrieve(BASE + name, path)
    print(f"{name}: {path.stat().st_size / 1e6:.1f} MB")

vision = ort.InferenceSession(str(CACHE / FILES[0]))
text = ort.InferenceSession(str(CACHE / FILES[1]))
for label, s in [("vision", vision), ("text", text)]:
    print(label, "inputs", [(i.name, i.type, i.shape) for i in s.get_inputs()],
          "outputs", [o.name for o in s.get_outputs()])
assert [i.name for i in vision.get_inputs()] == ["pixel_values"]
assert {i.name for i in text.get_inputs()} <= {"input_ids", "attention_mask"}
assert any(o.name.endswith("embeds") for o in vision.get_outputs())
assert any(o.name.endswith("embeds") for o in text.get_outputs())

# Same tokenizer data the app bundles (simple whitespace-separated words only, enough for this check).
bpe = Path(__file__).parents[1] / "app/src/main/assets/clip_bpe_simple_vocab_16e6.txt.gz"
merges = gzip.open(bpe, "rt", encoding="utf-8").read().split("\n")[1:49152 - 256 - 2 + 1]
merges = [tuple(m.split()) for m in merges]
bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
vocab = [chr(b) for b in bs] + [chr(256 + n) for n in range(256 - len(bs))]
vocab = vocab + [v + "</w>" for v in vocab] + ["".join(m) for m in merges] + ["<|startoftext|>", "<|endoftext|>"]
encoder = {v: i for i, v in enumerate(vocab)}
ranks = {m: i for i, m in enumerate(merges)}

def bpe_word(word):
    parts = list(word[:-1]) + [word[-1] + "</w>"]
    while len(parts) > 1:
        pairs = [(ranks.get((a, b), 1e9), i) for i, (a, b) in enumerate(zip(parts, parts[1:]))]
        rank, i = min(pairs)
        if rank == 1e9:
            break
        parts = parts[:i] + [parts[i] + parts[i + 1]] + parts[i + 2:]
    return parts

def encode(sentence):
    ids = [49406] + [encoder[p] for w in sentence.lower().split() for p in bpe_word(w)] + [49407]
    return np.array([ids], dtype=np.int64)

def embed_text(sentence):
    ids = encode(sentence)
    feeds = {"input_ids": ids, "attention_mask": np.ones_like(ids)}
    feeds = {i.name: feeds[i.name] for i in text.get_inputs()}
    out = dict(zip([o.name for o in text.get_outputs()], text.run(None, feeds)))
    v = next(v for k, v in out.items() if k.endswith("embeds"))[0]
    return v / np.linalg.norm(v)

MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)

def embed_image(img):
    w, h = img.size
    scale = 224 / min(w, h)
    img = img.resize((max(224, round(w * scale)), max(224, round(h * scale))), Image.BILINEAR)
    left, top = (img.width - 224) // 2, (img.height - 224) // 2
    px = (np.asarray(img.crop((left, top, left + 224, top + 224)), dtype=np.float32) / 255 - MEAN) / STD
    out = dict(zip([o.name for o in vision.get_outputs()],
                   vision.run(None, {"pixel_values": px.transpose(2, 0, 1)[None].astype(np.float32)})))
    v = next(v for k, v in out.items() if k.endswith("embeds"))[0]
    return v / np.linalg.norm(v)

labels = ["red", "blue", "text document"]
texts = np.stack([embed_text(f"a photo of {label}") for label in labels])

doc = Image.new("RGB", (480, 640), "white")
draw = ImageDraw.Draw(doc)
for y in range(40, 600, 24):
    draw.text((30, y), "Invoice total amount due date payment terms " * 2, fill="black")
cases = [(Image.new("RGB", (640, 480), (220, 20, 20)), "red"),
         (Image.new("RGB", (480, 640), (20, 40, 220)), "blue"),
         (doc, "text document")]
for img, expected in cases:
    logits = 100 * texts @ embed_image(img)
    probs = np.exp(logits - logits.max()); probs /= probs.sum()
    got = labels[int(probs.argmax())]
    print(f"expected {expected!r}: got {got!r} ({probs.max():.0%})")
    assert got == expected
print("CLIP models OK")
