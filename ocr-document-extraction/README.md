# OCR Document Extraction Example

Exercises **`OcrTool`** — renders a known text string onto a synthetic PNG, runs Tesseract via
Tess4J against it, and prints the extracted text. Tests both the `path` and `base64` source modes.

## Prerequisites

**Native library (required):** Tesseract + its English language pack.

| Platform         | Install                                                                    |
|------------------|-----------------------------------------------------------------------------|
| Debian / Ubuntu  | `sudo apt-get install tesseract-ocr tesseract-ocr-eng`                      |
| macOS (Homebrew) | `brew install tesseract`                                                    |
| Windows          | Installer from https://github.com/UB-Mannheim/tesseract/wiki                |

**Docker alternative** — run the example itself inside a Tesseract-equipped container:

```bash
# Pull an image with Tesseract pre-installed + Java 21
docker run --rm -it -v "$PWD":/workspace -w /workspace openjdk:21-jdk \
  bash -c "apt-get update && apt-get install -y tesseract-ocr && ./run.sh ocr"
```

**Optional env vars:**

| Env var                         | Purpose                                                    |
|---------------------------------|-------------------------------------------------------------|
| `swarmai.tools.ocr.tessdata-path` (Spring property) | Override auto-detected tessdata location      |
| `TESSDATA_PREFIX` (standard)    | Tesseract's own tessdata lookup variable                    |

The tool auto-detects tessdata at common install locations:
`/usr/share/tesseract-ocr/{4,5}/tessdata`, `/usr/share/tessdata`, `/opt/homebrew/share/tessdata`.

## Run

```bash
./run.sh ocr                                              # default invoice string
./run.sh ocr "SwarmAI integration test ABC-123"
./run.sh ocr "Multi-word sentence with punctuation!"
```

## What this proves about the tool

- Native-library loading: if Tesseract isn't installed the tool returns an install hint — not
  a cryptic `UnsatisfiedLinkError`.
- Input modes: `path` (local file) and `base64` (inline bytes) both round-trip the same image.
- `url` (remote image) mode is covered in the unit tests — uncomment in the example to try.
- Language codes: default `eng`; multi-lang support via `eng+deu` / `eng+fra` etc.
- Page segmentation mode (`page_seg_mode`) can be tuned for different document layouts.
- Output header reports image dimensions, language, and OCR latency — useful for benchmarking.
- Blank image / no text detected → clean "no text detected" hint rather than empty string.
