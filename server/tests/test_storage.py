import pytest

from app.storage import FailoverStorage, LocalDiskBackend

from .conftest import FailingCloudinary, R2Disk


def test_failover_cloudinary_to_r2(tmp_path, caplog):
    s = FailoverStorage(FailingCloudinary(), R2Disk(str(tmp_path / "r2")))
    with caplog.at_level("INFO", logger="sonex.storage"):
        backend = s.put("clips/1/x.wav", b"audio-bytes")
    assert backend == "r2"
    assert s.get("clips/1/x.wav") == b"audio-bytes"
    assert s.url("clips/1/x.wav").startswith("file://")
    # the serving backend is logged
    assert any("backend=r2" in rec.getMessage() for rec in caplog.records)
    s.delete("clips/1/x.wav")
    with pytest.raises(RuntimeError):
        s.get("clips/1/x.wav")


def test_primary_used_when_healthy(tmp_path):
    primary = LocalDiskBackend(str(tmp_path / "primary"))
    s = FailoverStorage(primary, R2Disk(str(tmp_path / "r2")))
    assert s.put("k", b"d") == "local"
    assert s.get("k") == b"d"


def test_all_backends_down(tmp_path):
    s = FailoverStorage(FailingCloudinary(), FailingCloudinary())
    with pytest.raises(RuntimeError):
        s.put("k", b"d")


def test_local_disk_rejects_traversal(tmp_path):
    b = LocalDiskBackend(str(tmp_path))
    with pytest.raises(ValueError):
        b.put("../evil", b"x")
