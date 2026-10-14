#!/bin/sh
# Downloads the CLIP model files into the app's assets so they ship inside the APK.
# Without them the app downloads the models from Hugging Face on first use instead.
set -eu
cd "$(dirname "$0")/../app/src/main/assets"
mkdir -p clip
for f in vision_model_quantized.onnx text_model_quantized.onnx; do
  [ -f "clip/$f" ] || curl -fL --retry 3 -o "clip/$f" "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/$f"
done
ls -l clip
