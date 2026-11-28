# Shared media contract fixtures

The four JSON files in v2/ are byte-for-byte copies from the published Media Index
[v2-contract-2.0.0 bundle](https://github.com/anilifecommunity/media-index/releases/tag/v2-contract-2.0.0).
Source: 7acf098c792fe53c280d98ef360ff4ea1f725bbd.
ZIP SHA256: 9a02b6e2d1cbb585c873137bff369f4decf262aa3dc22355be5f113d4b80f671.
They contain synthetic contract cases and dated public metadata, not live playback availability.
Preserve their bytes when updating the pin; native-specific migration cases belong in separate tests.
