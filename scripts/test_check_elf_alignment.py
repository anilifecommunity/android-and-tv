import struct
import zipfile

from check_elf_alignment import check_apk, elf_load_alignments


def elf64(alignment: int) -> bytes:
    data = bytearray(64 + 56)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 1, 1, alignment)
    return bytes(data)


def test_reads_load_alignment():
    assert elf_load_alignments(elf64(0x4000)) == (2, [0x4000])


def test_rejects_unaligned_64_bit_library(tmp_path):
    apk = tmp_path / "app.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("lib/arm64-v8a/libexample.so", elf64(0x1000))

    checked, failures = check_apk(apk)

    assert checked == 1
    assert "expected at least 16384" in failures[0]
