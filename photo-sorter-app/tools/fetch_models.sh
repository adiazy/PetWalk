#!/bin/sh
# Downloads the recognition models into the app's assets so they ship inside the APK.
#  - clip/: OpenAI CLIP ViT-B/32 (ONNX, int8) for "By content". Without it the app downloads
#    the model from Hugging Face on first use.
#  - face/: OpenCV SFace (Apache-2.0) for "Family". Required for Family mode.
set -eu
cd "$(dirname "$0")/../app/src/main/assets"
mkdir -p clip face
for f in vision_model_quantized.onnx text_model_quantized.onnx; do
  [ -f "clip/$f" ] || curl -fL --retry 3 -o "clip/$f" "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/$f"
done
f=face_recognition_sface_2021dec.onnx
[ -f "face/$f" ] || curl -fL --retry 3 -o "face/$f" \
  "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_recognition_sface/$f"
ls -l clip face
