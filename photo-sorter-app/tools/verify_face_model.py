"""Check that the app's face pipeline (5-landmark similarity alignment to the 112x112 template,
raw RGB CHW input, L2-normalized output) reproduces OpenCV's reference SFace pipeline.

Usage: python verify_face_model.py <sface.onnx> <yunet.onnx>
The app does the same steps in Kotlin (FaceAlign.kt, FaceEngine.kt) with ML Kit landmarks."""
import sys

import cv2
import numpy as np
import onnxruntime as ort
from skimage import data

sface_path, yunet_path = sys.argv[1], sys.argv[2]
TEMPLATE = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366],
                     [41.5493, 92.3655], [70.7299, 92.2041]])

options = ort.SessionOptions()
options.log_severity_level = 3  # the model lists its weights as inputs; ORT warns about each
session = ort.InferenceSession(sface_path, options)
inp, out = session.get_inputs()[0], session.get_outputs()[0]
print("input", inp.name, inp.shape, "output", out.name, out.shape)
assert list(inp.shape[1:]) == [3, 112, 112], inp.shape
assert out.shape[-1] == 128, out.shape


def detect(bgr):
    det = cv2.FaceDetectorYN.create(yunet_path, "", (bgr.shape[1], bgr.shape[0]), 0.8)
    _, faces = det.detect(bgr)
    assert faces is not None and len(faces) >= 1, "no face found"
    return faces[0]


def app_points(face):
    """What FaceAlign.orderedPoints does: eyes and mouth corners sorted left-to-right in the image."""
    lm = face[4:14].reshape(5, 2)
    eyes = sorted([lm[0], lm[1]], key=lambda p: p[0])
    mouth = sorted([lm[3], lm[4]], key=lambda p: p[0])
    return np.array([eyes[0], eyes[1], lm[2], mouth[0], mouth[1]])


def similarity(src):
    """Same closed form as FaceAlign.similarity."""
    ms, md = src.mean(0), TEMPLATE.mean(0)
    s, d = src - ms, TEMPLATE - md
    norm = (s ** 2).sum()
    a = (s[:, 0] * d[:, 0] + s[:, 1] * d[:, 1]).sum() / norm
    b = (s[:, 0] * d[:, 1] - s[:, 1] * d[:, 0]).sum() / norm
    m = np.array([[a, -b, 0], [b, a, 0]])
    m[:, 2] = md - m[:, :2] @ ms
    return m


def app_embedding(rgb, face):
    aligned = cv2.warpAffine(rgb, similarity(app_points(face)), (112, 112), flags=cv2.INTER_LINEAR)
    x = aligned.astype(np.float32).transpose(2, 0, 1)[None]  # raw 0-255 RGB, CHW
    v = session.run(None, {inp.name: x})[0][0]
    return v / np.linalg.norm(v)


def opencv_embedding(bgr, face):
    rec = cv2.FaceRecognizerSF.create(sface_path, "")
    v = rec.feature(rec.alignCrop(bgr, face)).flatten()
    return v / np.linalg.norm(v)


rgb = data.astronaut()
bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
face = detect(bgr)
ours, ref = app_embedding(rgb, face), opencv_embedding(bgr, face)
same = float(ours @ ref)
print(f"app pipeline vs OpenCV reference: cosine {same:.4f}")
assert same > 0.98, "alignment or preprocessing differs from OpenCV's SFace pipeline"

# The same face, rotated and rescaled, should still match (OpenCV's recommended threshold is 0.363).
h, w = rgb.shape[:2]
turned = cv2.warpAffine(rgb, cv2.getRotationMatrix2D((w / 2, h / 2), 12, 0.8), (w, h))
turned_face = detect(cv2.cvtColor(turned, cv2.COLOR_RGB2BGR))
moved = float(ours @ app_embedding(turned, turned_face))
print(f"same face rotated 12 degrees and scaled: cosine {moved:.4f}")
assert moved > 0.6

# A different subject (the cat photo) must not look like the same person.
cat = data.chelsea()
cat_crop = cv2.resize(cat[40:260, 120:340], (112, 112)).astype(np.float32).transpose(2, 0, 1)[None]
other = session.run(None, {inp.name: cat_crop})[0][0]
diff = float(ours @ (other / np.linalg.norm(other)))
print(f"face vs cat crop: cosine {diff:.4f}")
assert diff < 0.3
print("Face model OK")
