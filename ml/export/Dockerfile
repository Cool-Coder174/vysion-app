# Export and inspection toolbox pinned to the app's runtime (executorch==1.3.1, torch==2.12.0; ml/requirements.txt).
# Linux on purpose: PyTorch publishes no Intel-Mac wheels. Build once, then run any ml/export script inside it:
#   docker build -t vysion-ml:1.3.1 ml/export
#   docker run --rm -v "$PWD/ml/out":/out vysion-ml:1.3.1 python /tools/inspect_pte.py /out/vlm.pte
FROM python:3.12-slim
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends libgl1 libglib2.0-0 git && rm -rf /var/lib/apt/lists/*
RUN pip install -q --no-cache-dir --extra-index-url https://download.pytorch.org/whl/cpu \
      "torch==2.12.0" "executorch==1.3.1" "ultralytics==8.4.14" "opencv-python-headless<5"
COPY inspect_pte.py /tools/inspect_pte.py
WORKDIR /work
