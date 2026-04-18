#!/usr/bin/env python3
"""
RVC Bridge for Scarlet TTS
==========================

This script provides a bridge between Java (Scarlet) and RVC (Retrieval-based
Voice Conversion).  It accepts a WAV file, applies RVC voice conversion, and
outputs the converted WAV file.

Usage:
    python rvc_bridge.py --status
    python rvc_bridge.py --install [--device cpu|auto]
    python rvc_bridge.py --input <in.wav> --output <out.wav> --model <model.pth> [options]

Requirements:
    - Python 3.9+
    - torch, torchaudio          (pip install torch torchaudio)
    - rvc-python                 (pip install rvc-python)
    - ffmpeg                     (system binary, for non-WAV inputs)

Detection:
    --status returns a rich JSON describing system (OS/arch), Python
    version, FFmpeg availability, every detected GPU (NVIDIA / AMD /
    Intel XPU / DirectML) and a recommended torch wheel index.  Pre-torch
    detection uses nvidia-smi / rocm-smi / WMIC so a GPU is visible even
    before torch is installed.

Exit codes:
    0  success
    1  error  (details in JSON on stdout)
    2  dependency missing — run --install first
"""

import argparse
import contextlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import traceback
from pathlib import Path


# ===========================================================================
# Status schema version — bumped when the top-level JSON shape changes.
# Old consumers that only read {gpu, dependencies_missing, rvc_compatible,
# models_available, models_dir} continue to work because those fields are
# preserved alongside the new ones.
# ===========================================================================
STATUS_SCHEMA_VERSION = 2

# Python version required by rvc-python.
#
# Lower bound: 3.9  — older interpreters lack features torch/rvc-python need.
# Upper bound: 3.11 — rvc-python transitively pins numpy<=1.25.3 and
#                     fairseq==0.12.2.  Neither has wheels for Python 3.12+,
#                     and numpy 1.25.2 source builds fail on 3.12/3.13 because
#                     setuptools' pkg_resources references pkgutil.ImpImporter,
#                     which was removed in 3.12.  Users on 3.12/3.13 hit:
#                         AttributeError: module 'pkgutil' has no attribute
#                         'ImpImporter'
#                     during `pip install rvc-python`.
#
# If/when rvc-python lifts the numpy pin, this upper bound can be raised.
MIN_PYTHON = (3, 9)
MAX_PYTHON = (3, 11)


# ---------------------------------------------------------------------------
# Low-level helpers
# ---------------------------------------------------------------------------

def _print_stderr(msg):
    print(msg, file=sys.stderr, flush=True)


def _try_import(module_name):
    """Return the module if importable, else None."""
    import importlib
    try:
        return importlib.import_module(module_name)
    except Exception:
        # Use broad Exception here — a broken install can raise things
        # other than ImportError (DLL errors, SyntaxError from py2 wheels…).
        return None


def _run(cmd, timeout=10):
    """
    Run a subprocess and return (returncode, stdout, stderr).
    Returns (None, "", "<reason>") if the executable is missing or times out,
    so callers never need to wrap this in try/except just for OSError.
    """
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or ""), (r.stderr or "")
    except FileNotFoundError:
        return None, "", "executable not found"
    except subprocess.TimeoutExpired:
        return None, "", f"timed out after {timeout}s"
    except Exception as exc:
        return None, "", f"{type(exc).__name__}: {exc}"


def _which(name):
    """Wrap shutil.which so the caller can rely on returning str|None."""
    path = shutil.which(name)
    return path if path else None


# ---------------------------------------------------------------------------
# System / Python info
# ---------------------------------------------------------------------------

def _detect_wsl():
    """Return True if running under Windows Subsystem for Linux."""
    if platform.system() != "Linux":
        return False
    try:
        with open("/proc/version", "r") as f:
            body = f.read().lower()
        return "microsoft" in body or "wsl" in body
    except Exception:
        return False


def get_system_info():
    """
    OS / arch / host info — entirely independent of torch or any ML deps.
    This is what the Java side uses to decide *what to install*.
    """
    system  = platform.system()          # 'Windows' / 'Linux' / 'Darwin'
    machine = platform.machine()         # 'x86_64' / 'AMD64' / 'arm64' / …
    release = platform.release()
    version = platform.version()

    # Normalise arch to a small, predictable set.
    m = machine.lower()
    if m in ("x86_64", "amd64", "x64"):
        arch = "x86_64"
    elif m in ("arm64", "aarch64"):
        arch = "arm64"
    elif m in ("i386", "i686", "x86"):
        arch = "i386"
    else:
        arch = machine or "unknown"

    return {
        "os":          system,
        "os_release":  release,
        "os_version":  version,
        "arch":        arch,
        "machine_raw": machine,
        "is_wsl":      _detect_wsl(),
        "hostname":    platform.node(),
    }


def get_python_info():
    """Python interpreter details and MIN_PYTHON–MAX_PYTHON range check.

    ``is_compatible`` means the current Python is usable for RVC installation:
    at least ``MIN_PYTHON`` AND at most ``MAX_PYTHON`` (both inclusive).  A too-new
    Python will fail ``pip install rvc-python`` because numpy<=1.25.3 has no
    wheels for 3.12+ and its sdist fails to build.  See comment on MAX_PYTHON.
    """
    vi = sys.version_info
    cur = (vi.major, vi.minor)
    too_old = cur < MIN_PYTHON
    too_new = cur > MAX_PYTHON
    if too_old:
        reason = (f"Python {vi.major}.{vi.minor} is below the minimum "
                  f"required ({MIN_PYTHON[0]}.{MIN_PYTHON[1]}).")
    elif too_new:
        reason = (f"Python {vi.major}.{vi.minor} is newer than the maximum "
                  f"supported ({MAX_PYTHON[0]}.{MAX_PYTHON[1]}).  "
                  f"rvc-python depends on numpy<=1.25.3 and fairseq==0.12.2, "
                  f"neither of which has wheels for Python 3.12+.  "
                  f"Install Python {MAX_PYTHON[0]}.{MAX_PYTHON[1]} "
                  f"(or {MIN_PYTHON[0]}.{MIN_PYTHON[1]}–"
                  f"{MAX_PYTHON[0]}.{MAX_PYTHON[1]}) and re-run.")
    else:
        reason = None
    return {
        "version":         f"{vi.major}.{vi.minor}.{vi.micro}",
        "version_tuple":   [vi.major, vi.minor, vi.micro],
        "executable":      sys.executable,
        "implementation":  platform.python_implementation(),  # 'CPython', 'PyPy'…
        "is_compatible":   (not too_old) and (not too_new),
        "too_old":         too_old,
        "too_new":         too_new,
        "min_required":    f"{MIN_PYTHON[0]}.{MIN_PYTHON[1]}",
        "max_supported":   f"{MAX_PYTHON[0]}.{MAX_PYTHON[1]}",
        "incompatible_reason": reason,
    }


# ---------------------------------------------------------------------------
# FFmpeg
# ---------------------------------------------------------------------------

def check_ffmpeg():
    """FFmpeg is required for non-WAV inputs.  Works pre-torch."""
    path = _which("ffmpeg")
    if not path:
        return {"available": False, "path": None, "version": None}

    rc, out, _ = _run([path, "-version"], timeout=5)
    version = None
    if rc == 0 and out:
        # First line looks like: "ffmpeg version 6.1.1 Copyright (c) ..."
        m = re.search(r"ffmpeg version (\S+)", out)
        if m:
            version = m.group(1)
    return {"available": True, "path": path, "version": version}


# ---------------------------------------------------------------------------
# CUDA toolkit / driver probing
# ---------------------------------------------------------------------------

def _parse_nvidia_smi_gpus():
    """
    Pre-torch NVIDIA GPU detection.  Returns a list of dicts or [] on any
    error.  nvidia-smi is installed with the driver, so this works even
    before torch is installed.
    """
    rc, out, _ = _run([
        "nvidia-smi",
        "--query-gpu=index,name,memory.total,driver_version,compute_cap",
        "--format=csv,noheader,nounits",
    ], timeout=5)
    if rc != 0 or not out.strip():
        return []

    gpus = []
    for line in out.strip().splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) < 4:
            continue
        idx_s, name, mem_s, driver = parts[0], parts[1], parts[2], parts[3]
        compute = parts[4] if len(parts) >= 5 else None
        try:
            idx = int(idx_s)
        except ValueError:
            idx = 0
        try:
            # memory.total in MiB → GB
            mem_gb = round(float(mem_s) / 1024.0, 1)
        except ValueError:
            mem_gb = 0.0
        gpus.append({
            "index":                idx,
            "backend":              "cuda",
            "name":                 name,
            "memory_gb":            mem_gb,
            "driver_version":       driver,
            "compute_capability":   compute,
            "detected_via":         "nvidia-smi",
            "available":            True,
            "device":               f"cuda:{idx}",
        })
    return gpus


def _parse_nvidia_smi_cuda_version():
    """
    Get the CUDA runtime version the driver supports ("12.4" etc).  Returns
    None if nvidia-smi is missing.  Note: this is the DRIVER cap, not an
    installed toolkit — which is what we actually care about for wheel
    selection because the runtime ships inside torch.
    """
    rc, out, _ = _run(["nvidia-smi"], timeout=5)
    if rc != 0 or not out:
        return None
    m = re.search(r"CUDA Version:\s*(\d+\.\d+)", out)
    return m.group(1) if m else None


def _parse_nvcc_version():
    """Installed CUDA toolkit version from nvcc, or None."""
    rc, out, _ = _run(["nvcc", "--version"], timeout=5)
    if rc != 0 or not out:
        return None
    # e.g. "Cuda compilation tools, release 12.1, V12.1.105"
    m = re.search(r"release\s+(\d+\.\d+)", out)
    return m.group(1) if m else None


def _parse_rocm_smi_gpus():
    """Pre-torch AMD ROCm detection on Linux.  Returns [] on any error."""
    # rocm-smi has notoriously inconsistent flags across versions; keep this
    # simple and robust.
    rc, out, _ = _run(["rocm-smi", "--showproductname", "--showmeminfo", "vram"],
                      timeout=5)
    if rc != 0 or not out:
        return []

    gpus = []
    current_idx = None
    current_name = None
    current_mem = 0.0
    for line in out.splitlines():
        line = line.strip()
        m_card = re.match(r"GPU\[(\d+)\]", line)
        if m_card:
            if current_idx is not None:
                gpus.append({
                    "index":          current_idx,
                    "backend":        "rocm",
                    "name":           current_name or "AMD GPU",
                    "memory_gb":      current_mem,
                    "driver_version": None,
                    "detected_via":   "rocm-smi",
                    "available":      True,
                    # PyTorch+ROCm presents ROCm GPUs via the cuda: device string
                    "device":         f"cuda:{current_idx}",
                })
            current_idx = int(m_card.group(1))
            current_name = None
            current_mem = 0.0
        if "Card series" in line or "Card Series" in line:
            # line like "GPU[0]    : Card series: Navi 31 [Radeon RX 7900 XTX]"
            m = re.search(r":\s*([^:]+?)\s*$", line)
            if m:
                current_name = m.group(1).strip()
        if "Total memory" in line or "VRAM Total Memory" in line:
            m = re.search(r"(\d+)\s*(?:B|bytes)?$", line)
            if m:
                try:
                    current_mem = round(int(m.group(1)) / (1024 ** 3), 1)
                except ValueError:
                    pass
    if current_idx is not None:
        gpus.append({
            "index":          current_idx,
            "backend":        "rocm",
            "name":           current_name or "AMD GPU",
            "memory_gb":      current_mem,
            "driver_version": None,
            "detected_via":   "rocm-smi",
            "available":      True,
            "device":         f"cuda:{current_idx}",
        })
    return gpus


def _parse_windows_gpus():
    """
    Windows fallback GPU detection via PowerShell CIM (preferred) or WMIC
    (legacy).  Used to spot any GPU — NVIDIA, AMD, Intel — before torch
    is installed, so the installer can pick the right wheel.
    """
    if platform.system() != "Windows":
        return []

    # Try PowerShell first — WMIC is deprecated on Windows 11.
    rc, out, _ = _run([
        "powershell", "-NoProfile", "-Command",
        "Get-CimInstance Win32_VideoController | "
        "Select-Object Name, AdapterRAM, AdapterCompatibility, PNPDeviceID | ConvertTo-Json -Compress",
    ], timeout=10)
    if rc == 0 and out.strip():
        try:
            data = json.loads(out)
            if isinstance(data, dict):
                data = [data]
            gpus = []
            for i, entry in enumerate(data):
                name = (entry.get("Name") or "").strip()
                if not name:
                    continue
                ram = entry.get("AdapterRAM") or 0
                try:
                    mem_gb = round(float(ram) / (1024 ** 3), 1)
                except (TypeError, ValueError):
                    mem_gb = 0.0
                backend = _classify_gpu(entry)
                gpus.append({
                    "index":          i,
                    "backend":        backend,
                    "name":           name,
                    "memory_gb":      mem_gb,
                    "driver_version": None,
                    "detected_via":   "powershell-cim",
                    "available":      True,
                    "device":         _backend_device(backend, i),
                })
            if gpus:
                return gpus
        except Exception:
            pass

    # Legacy WMIC fallback.
    rc, out, _ = _run(["wmic", "path", "win32_VideoController", "get",
                       "name,AdapterRAM,AdapterCompatibility,PNPDeviceID", "/format:csv"], timeout=10)
    if rc != 0 or not out.strip():
        return []

    gpus = []
    lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
    # First non-empty line is the CSV header.
    if len(lines) < 2:
        return []
    header = [h.strip().lower() for h in lines[0].split(",")]
    try:
        name_col = header.index("name")
        ram_col = header.index("adapterram")
        compat_col = header.index("adaptercompatibility")
        pnp_col = header.index("pnpdeviceid")
    except ValueError:
        compat_col = -1
        pnp_col = -1

    for i, row in enumerate(lines[1:]):
        cols = [c.strip() for c in row.split(",")]
        if len(cols) <= max(name_col, ram_col):
            continue
        name = cols[name_col]
        if not name:
            continue
        try:
            mem_gb = round(float(cols[ram_col]) / (1024 ** 3), 1)
        except (ValueError, IndexError):
            mem_gb = 0.0
        entry = {
            "Name": name,
            "AdapterCompatibility": cols[compat_col] if compat_col >= 0 and len(cols) > compat_col else "",
            "PNPDeviceID": cols[pnp_col] if pnp_col >= 0 and len(cols) > pnp_col else "",
        }
        backend = _classify_gpu(entry)
        gpus.append({
            "index":          i,
            "backend":        backend,
            "name":           name,
            "memory_gb":      mem_gb,
            "driver_version": None,
            "detected_via":   "wmic",
            "available":      True,
            "device":         _backend_device(backend, i),
        })
    return gpus


def _classify_gpu(entry):
    """
    Rough backend classification from Windows adapter metadata.
    Prefer vendor / PCI-id signals when available, then fall back to broad
    marketing-name matching.
    """
    name = ""
    compat = ""
    pnp = ""
    if isinstance(entry, dict):
        name = (entry.get("Name") or "").strip()
        compat = (entry.get("AdapterCompatibility") or "").strip()
        pnp = (entry.get("PNPDeviceID") or "").strip()
    else:
        name = str(entry or "").strip()

    n = name.lower()
    c = compat.lower()
    p = pnp.lower()

    # PCI vendor 10DE = NVIDIA
    if "ven_10de" in p or "nvidia" in c:
        return "cuda"
    if any(k in n for k in (
        "nvidia", "geforce", "quadro", "tesla", "rtx", "gtx",
        "titan", "mx", "grid", "nvs", "cmp", "rtx a", "a100", "a30", "a40", "a16"
    )):
        return "cuda"

    # PCI vendor 1002 = AMD/ATI
    if "ven_1002" in p or "advanced micro devices" in c or "amd" in c or "ati" in c:
        return "directml" if platform.system() == "Windows" else "rocm"
    if any(k in n for k in ("radeon", "amd", "firepro", "instinct")):
        # AMD on Windows = DirectML (ROCm doesn't run on Windows).
        return "directml" if platform.system() == "Windows" else "rocm"

    # PCI vendor 8086 = Intel
    if "ven_8086" in p or "intel" in c:
        return "xpu" if "arc" in n else "directml"
    if any(k in n for k in ("intel", "arc", "iris", "uhd")):
        # Intel on Windows can use DirectML or XPU; XPU wheel exists, so prefer
        # XPU on any system and let the runtime decide.
        return "xpu" if "arc" in n else "directml"
    return "unknown"


def _backend_device(backend, idx):
    if backend == "cuda":
        return f"cuda:{idx}"
    if backend == "rocm":
        return f"cuda:{idx}"  # HIP presents as cuda:
    if backend == "xpu":
        return f"xpu:{idx}"
    if backend == "directml":
        return f"privateuseone:{idx}"
    return "cpu"


# ---------------------------------------------------------------------------
# Torch-backed GPU probing (only available once torch is installed)
# ---------------------------------------------------------------------------

def _probe_torch_cuda(torch):
    """Enumerate CUDA (or ROCm-as-CUDA) devices that torch can actually use."""
    gpus = []
    try:
        if not torch.cuda.is_available():
            return gpus
        n = torch.cuda.device_count()
        # torch.version.hip is set for ROCm builds; falls back to CUDA otherwise
        is_rocm = bool(getattr(torch.version, "hip", None))
        backend = "rocm" if is_rocm else "cuda"
        for i in range(n):
            try:
                props = torch.cuda.get_device_properties(i)
                gpus.append({
                    "index":              i,
                    "backend":            backend,
                    "name":               torch.cuda.get_device_name(i),
                    "memory_gb":          round(props.total_memory / (1024 ** 3), 1),
                    "compute_capability": f"{props.major}.{props.minor}",
                    "cuda_version":       torch.version.cuda,
                    "hip_version":        getattr(torch.version, "hip", None),
                    "detected_via":       "torch",
                    "available":          True,
                    "device":             f"cuda:{i}",
                })
            except Exception as exc:
                _print_stderr(f"[RVC] torch.cuda.get_device_properties({i}) failed: {exc}")
    except Exception as exc:
        _print_stderr(f"[RVC] torch CUDA probe failed: {exc}")
    return gpus


def _probe_torch_xpu():
    """Intel XPU via intel_extension_for_pytorch."""
    ipex = _try_import("intel_extension_for_pytorch")
    if ipex is None:
        return []
    torch = _try_import("torch")
    if torch is None or not hasattr(torch, "xpu"):
        return []
    gpus = []
    try:
        if not torch.xpu.is_available():
            return []
        n = torch.xpu.device_count()
        for i in range(n):
            try:
                name = torch.xpu.get_device_name(i)
            except Exception:
                name = f"Intel XPU {i}"
            gpus.append({
                "index":        i,
                "backend":      "xpu",
                "name":         name,
                "memory_gb":    0.0,  # XPU API doesn't expose memory reliably
                "detected_via": "torch-xpu",
                "available":    True,
                "device":       f"xpu:{i}",
            })
    except Exception as exc:
        _print_stderr(f"[RVC] XPU probe failed: {exc}")
    return gpus


def _probe_torch_directml():
    """Microsoft DirectML (Windows, any DX12 GPU)."""
    dml = _try_import("torch_directml")
    if dml is None:
        return []
    gpus = []
    try:
        n = dml.device_count() if hasattr(dml, "device_count") else 1
        for i in range(n):
            try:
                name = dml.device_name(i) if hasattr(dml, "device_name") else f"DirectML {i}"
            except Exception:
                name = f"DirectML device {i}"
            gpus.append({
                "index":        i,
                "backend":      "directml",
                "name":         name,
                "memory_gb":    0.0,
                "detected_via": "torch-directml",
                "available":    True,
                "device":       f"privateuseone:{i}",
            })
    except Exception as exc:
        _print_stderr(f"[RVC] DirectML probe failed: {exc}")
    return gpus


def detect_all_gpus():
    """
    Aggregate every GPU we can see, using every available backend.
    De-duplicates by (backend, index, name) so a device found via both
    nvidia-smi and torch shows up once (preferring the torch entry for
    richer info like compute capability).
    """
    torch = _try_import("torch")

    all_gpus = []

    # Prefer torch-backed info when available (it includes compute capability
    # and confirms torch can actually use the device).
    if torch is not None:
        all_gpus.extend(_probe_torch_cuda(torch))
    all_gpus.extend(_probe_torch_xpu())
    all_gpus.extend(_probe_torch_directml())

    # Pre-torch SMI / WMI sweeps — these catch GPUs that torch can't currently
    # use (e.g. torch not installed, or CPU-only wheel installed on a CUDA box).
    nvidia = _parse_nvidia_smi_gpus()
    rocm   = _parse_rocm_smi_gpus()
    winfb  = _parse_windows_gpus()

    # Merge, preferring torch-backed entries.
    seen = set()
    def _key(g):
        return (g.get("backend"), g.get("index"), (g.get("name") or "").lower())
    for g in all_gpus:
        seen.add(_key(g))
    for extra in (nvidia + rocm + winfb):
        k = _key(extra)
        if k in seen:
            continue
        # Also skip if we already have a torch-backed entry with the same
        # (backend, index) — the SMI entry is redundant then.
        if any((g.get("backend") == extra.get("backend")
                and g.get("index")   == extra.get("index"))
               for g in all_gpus):
            continue
        all_gpus.append(extra)
        seen.add(k)

    return all_gpus


def pick_primary_gpu(gpus):
    """
    Choose a single 'preferred' GPU for the legacy `gpu` field.  Order of
    preference: NVIDIA CUDA > ROCm > XPU > DirectML, with the largest VRAM
    winning inside each backend.
    """
    rank = {"cuda": 0, "rocm": 1, "xpu": 2, "directml": 3, "unknown": 9}
    if not gpus:
        return None
    sorted_gpus = sorted(
        gpus,
        key=lambda g: (rank.get(g.get("backend"), 9), -float(g.get("memory_gb") or 0)),
    )
    return sorted_gpus[0]


def legacy_gpu_dict(primary):
    """Shape the primary GPU into the pre-v2 `gpu` field for old consumers."""
    if primary is None:
        return {
            "available":    False,
            "device":       "cpu",
            "name":         "CPU",
            "memory_gb":    0,
            "cuda_version": None,
        }
    return {
        "available":    bool(primary.get("available", True)),
        "device":       primary.get("device", "cpu"),
        "name":         primary.get("name", "Unknown"),
        "memory_gb":    float(primary.get("memory_gb") or 0),
        "cuda_version": primary.get("cuda_version"),
    }


# ---------------------------------------------------------------------------
# Dependencies
# ---------------------------------------------------------------------------

# Required packages — rvc-python will not work at all if any are missing.
# Keyed by import-name, value is the pip distribution name.
REQUIRED_PACKAGES = {
    "torch":       "torch",
    "torchaudio":  "torchaudio",
    "rvc_python":  "rvc-python",
}

# Optional packages — nice-to-have extras that the installer will still try
# to pull down, but whose absence does NOT flip `rvc_compatible` to false.
# torchcodec is the usual offender here: it enables faster video-backed
# audio loading but has sparse Windows wheel coverage, and rvc-python 0.1.x
# does not import it at runtime — so the conversion pipeline happily runs
# with only torch + torchaudio.
OPTIONAL_PACKAGES = {
    "torchcodec":  "torchcodec",
}

# Combined view for status reporting — keep the old CORE_PACKAGES name as an
# alias so any code elsewhere that still references it keeps working.
CORE_PACKAGES = {**REQUIRED_PACKAGES, **OPTIONAL_PACKAGES}


def _package_version(module_name):
    """Return the installed version string of a package, or None."""
    mod = _try_import(module_name)
    if mod is None:
        return None
    for attr in ("__version__", "version", "VERSION"):
        v = getattr(mod, attr, None)
        if isinstance(v, str):
            return v
    # Fall back to importlib.metadata for packages that don't expose __version__
    try:
        from importlib import metadata
        dist = CORE_PACKAGES.get(module_name, module_name)
        return metadata.version(dist)
    except Exception:
        return None


def check_dependencies():
    """
    Return a list of missing *required* pip distribution names.

    Optional packages (see OPTIONAL_PACKAGES) are intentionally excluded —
    the installer still attempts to install them, but the UI should not
    treat their absence as blocking for rvc_compatible.
    """
    missing = []
    for import_name, pip_name in REQUIRED_PACKAGES.items():
        if _try_import(import_name) is None:
            missing.append(pip_name)
    return missing


def check_optional_missing():
    """Return a list of missing optional pip distribution names."""
    missing = []
    for import_name, pip_name in OPTIONAL_PACKAGES.items():
        if _try_import(import_name) is None:
            missing.append(pip_name)
    return missing


def package_versions():
    """Map of installed package versions (None when missing)."""
    return {pip_name: _package_version(import_name)
            for import_name, pip_name in CORE_PACKAGES.items()}


def _refresh_import_caches():
    """
    Invalidate finder / metadata caches so a pip install we just ran is
    visible to subsequent `_try_import` / `metadata.version` calls in the
    same process.  Without this, Python's FileFinder can keep a stale
    directory listing and claim the package is still missing even though
    its files are on disk.
    """
    try:
        import importlib
        importlib.invalidate_caches()
    except Exception:
        pass
    try:
        from importlib import metadata as _md
        # Force re-discovery of installed distributions.
        _md.distributions()  # side-effect: re-scans sys.path
    except Exception:
        pass


def _prepare_torch_for_rvc_checkpoints():
    """
    PyTorch 2.6+ changed the default of ``torch.load`` from
    ``weights_only=False`` to ``weights_only=True``.  rvc-python's fairseq
    hubert checkpoint pickles ``fairseq.data.dictionary.Dictionary``, which
    the new "safe" loader refuses to unpickle, raising::

        _pickle.UnpicklingError: Weights only load failed ...
        Please file an issue with the following so that we can make
        `weights_only=True` compatible with your use case ...

    Older rvc-python releases call ``torch.load`` without specifying
    ``weights_only``, so they inherit the new default and blow up.  We
    transparently patch ``torch.load`` to restore the old default (only
    when the caller hasn't supplied one), AND register the known fairseq
    classes on the safe-globals allowlist as belt-and-suspenders.

    Must be called before any ``rvc_python`` import so the patch is in
    effect the first time the library touches ``torch.load``.
    """
    try:
        import torch  # type: ignore
    except Exception as exc:
        _print_stderr(f"[RVC] could not import torch for checkpoint prep: {exc}")
        return

    # Belt-and-suspenders: allowlist fairseq's Dictionary (+ a few other
    # classes rvc-python commonly pickles) so even a future version that
    # re-enables weights_only=True keeps working.
    try:
        safe_classes = []
        try:
            from fairseq.data.dictionary import Dictionary as _FairseqDict  # type: ignore
            safe_classes.append(_FairseqDict)
        except Exception:
            pass
        try:
            from fairseq.data.dictionary import TruncatedDictionary as _FairseqTruncDict  # type: ignore
            safe_classes.append(_FairseqTruncDict)
        except Exception:
            pass
        try:
            import collections as _coll
            safe_classes.append(_coll.OrderedDict)
        except Exception:
            pass
        if safe_classes and hasattr(torch, "serialization"):
            add = getattr(torch.serialization, "add_safe_globals", None)
            if callable(add):
                try:
                    add(safe_classes)
                except Exception as exc:
                    _print_stderr(f"[RVC] add_safe_globals failed: {exc}")
    except Exception as exc:
        _print_stderr(f"[RVC] safe-globals setup failed: {exc}")

    # Primary fix: flip torch.load's default back to weights_only=False.
    try:
        original = torch.load
        if not getattr(original, "_scarlet_patched", False):
            def _patched_load(*args, **kwargs):
                # Only override if the caller didn't pass it explicitly.
                kwargs.setdefault("weights_only", False)
                return original(*args, **kwargs)
            _patched_load._scarlet_patched = True
            _patched_load._scarlet_original = original
            torch.load = _patched_load
    except Exception as exc:
        _print_stderr(f"[RVC] could not patch torch.load: {exc}")


# ---------------------------------------------------------------------------
# External RVC-WebUI install discovery
# ---------------------------------------------------------------------------

# Marker files that identify an RVC-WebUI / Mangio-RVC install.
RVC_MARKERS = ("infer-web.py", "go-web.bat", "go-web.sh", "configs/config.json")


def _looks_like_rvc_install(path: Path):
    """True if `path` contains any of the RVC-WebUI marker files."""
    if not path or not path.is_dir():
        return False
    return any((path / m).exists() for m in RVC_MARKERS)


def _candidate_install_paths():
    """Common places users drop an RVC-WebUI install."""
    cands = []
    # Explicit env var wins.
    envp = os.environ.get("SCARLET_RVC_PATH")
    if envp:
        cands.append(Path(envp))

    home = Path.home()
    cands += [
        home / "RVC",
        home / "Retrieval-based-Voice-Conversion-WebUI",
        home / "Mangio-RVC-Fork",
        home / "Desktop" / "RVC",
        home / "Documents" / "RVC",
    ]

    if platform.system() == "Windows":
        cands += [
            Path("C:/RVC"),
            Path("C:/Retrieval-based-Voice-Conversion-WebUI"),
            Path("C:/Mangio-RVC-Fork"),
            Path("D:/RVC"),
        ]
    return cands


def discover_external_rvc_installs():
    """List any RVC-WebUI-style installs we can find on disk."""
    found = []
    seen = set()
    for p in _candidate_install_paths():
        try:
            rp = p.resolve()
        except Exception:
            continue
        if rp in seen:
            continue
        seen.add(rp)
        if _looks_like_rvc_install(rp):
            found.append({
                "path": str(rp),
                "kind": _classify_rvc_install(rp),
                "has_config": (rp / "configs" / "config.json").exists(),
            })
    return found


def _classify_rvc_install(path: Path):
    name = path.name.lower()
    if "mangio" in name:
        return "mangio-rvc-fork"
    if "webui" in name or (path / "go-web.bat").exists():
        return "rvc-webui"
    return "rvc"


# ---------------------------------------------------------------------------
# Wheel / install recommendations
# ---------------------------------------------------------------------------

def recommend_torch_wheel(system_info, gpus):
    """
    Decide which pip index / extras to use for torch.  Returns a dict:
      { "label": "CUDA 12.1", "index_url": "...", "extras": ["torch","torchaudio"],
        "reason": "...", "device_hint": "cuda" }
    """
    primary = pick_primary_gpu(gpus)
    backend = primary.get("backend") if primary else None

    if backend == "cuda":
        cuda_v = _parse_nvidia_smi_cuda_version() or _parse_nvcc_version()
        # Pick the best-available wheel the PyTorch project still publishes.
        # Uses tuple comparison so future CUDA majors (13, 14, ...) naturally
        # pick the highest wheel we know about instead of falling through
        # to CPU the way the old `major >= 12 and minor >= 4` check did.
        if cuda_v:
            major_minor = cuda_v.split(".")[:2]
            try:
                major = int(major_minor[0]); minor = int(major_minor[1])
            except (ValueError, IndexError):
                major = minor = 0
            ver = (major, minor)
            if ver >= (12, 4):
                # CUDA 12.4 and everything newer (incl. 13.x) → cu124 wheels.
                # The CUDA runtime ships inside torch itself, and the NVIDIA
                # driver is forward-compatible with older runtimes, so a 13.0
                # driver happily runs cu124-compiled torch.
                tag, idx = "cu124", "https://download.pytorch.org/whl/cu124"
            elif ver >= (12, 0):
                tag, idx = "cu121", "https://download.pytorch.org/whl/cu121"
            elif ver >= (11, 0):
                tag, idx = "cu118", "https://download.pytorch.org/whl/cu118"
            else:
                tag, idx = "cpu", "https://download.pytorch.org/whl/cpu"
        else:
            # CUDA GPU but no nvidia-smi/nvcc version — safest bet is cu121.
            tag, idx = "cu121", "https://download.pytorch.org/whl/cu121"
        return {
            "label":       f"torch + torchaudio ({tag.upper()})",
            "tag":         tag,
            "index_url":   idx,
            "reason":      f"NVIDIA GPU detected (CUDA driver {cuda_v or 'unknown'})",
            "device_hint": "cuda",
        }

    if backend == "rocm":
        return {
            "label":       "torch + torchaudio (ROCm 6.0)",
            "tag":         "rocm6.0",
            "index_url":   "https://download.pytorch.org/whl/rocm6.0",
            "reason":      "AMD GPU detected and ROCm supported on this OS",
            "device_hint": "cuda",   # HIP presents as cuda:N
        }

    if backend == "xpu":
        return {
            "label":       "torch + intel_extension_for_pytorch (XPU)",
            "tag":         "xpu",
            "index_url":   "https://pytorch-extension.intel.com/release-whl/stable/xpu/us/",
            "reason":      "Intel Arc / Data Center GPU detected",
            "device_hint": "xpu",
        }

    if backend == "directml":
        return {
            "label":       "torch + torch-directml (Windows)",
            "tag":         "directml",
            "index_url":   None,     # default PyPI
            "reason":      "Non-NVIDIA GPU on Windows — DirectML is the portable option",
            "device_hint": "privateuseone",
        }

    return {
        "label":       "torch + torchaudio (CPU)",
        "tag":         "cpu",
        "index_url":   "https://download.pytorch.org/whl/cpu",
        "reason":      "No compatible GPU detected",
        "device_hint": "cpu",
    }


# ---------------------------------------------------------------------------
# Install orchestration
# ---------------------------------------------------------------------------

def install_dependencies(device_hint="auto"):
    """
    Install all required Python packages via pip.

    device_hint:
        "auto" — use recommend_torch_wheel() based on detected GPUs.
        "cpu"  — force CPU wheels.
        anything else is treated as a backend name and mapped through the
        recommender.

    Writes progress to stderr so Java only sees JSON on stdout.
    Returns (success: bool, message: str).
    """
    # Abort early on an unsupported Python (either too old OR too new).
    py = get_python_info()
    if not py["is_compatible"]:
        reason = py.get("incompatible_reason") or (
            f"Python {py['version']} is outside the supported range "
            f"{py['min_required']}\u2013{py['max_supported']}."
        )
        return False, (
            f"RVC install aborted: {reason}\n"
            f"Supported range: Python {py['min_required']}\u2013{py['max_supported']} "
            f"(inclusive).  Current interpreter: {py['executable']}"
        )

    pip = [sys.executable, "-m", "pip", "install", "--upgrade"]
    sys_info = get_system_info()
    gpus     = detect_all_gpus()

    # Decide which torch wheel we actually want.
    if device_hint == "cpu":
        wheel = {
            "label": "torch + torchaudio (CPU, forced)",
            "tag": "cpu",
            "index_url": "https://download.pytorch.org/whl/cpu",
            "device_hint": "cpu",
            "reason": "user-selected CPU mode",
        }
    else:
        wheel = recommend_torch_wheel(sys_info, gpus)

    _print_stderr(f"[RVC install] Target wheel: {wheel['label']}  ({wheel['reason']})")

    # Snapshot what's already installed so we can skip redundant work.  This
    # is important on Windows, where re-installing torch while any process
    # (like the Scarlet JVM that just ran `--status`) still has torch DLLs
    # mapped triggers WinError 5 "access denied" — the failure we kept
    # seeing after only `torchcodec` was actually missing.
    already = package_versions()  # {"torch": "2.11.0+cpu" or None, ...}
    installed_names = [name for name, v in already.items() if v]
    missing_names   = [name for name, v in already.items() if not v]
    _print_stderr(
        f"[RVC install] Already installed: {installed_names or 'none'}; "
        f"missing: {missing_names or 'none'}"
    )

    # --- Step 1: torch + torchaudio ----------------------------------------
    # Only run this step when at least one of torch / torchaudio is actually
    # missing. If both are present we leave them alone — even if the wheel
    # tag differs from the currently-installed build — because forcing a
    # reinstall to switch between cpu↔cuda wheels is both slow and prone to
    # OS-level lock errors on Windows. A user who explicitly wants to swap
    # wheels can uninstall torch first and re-run the installer.
    torch_needed = any(_package_version(m) is None for m in ("torch", "torchaudio"))
    if torch_needed:
        torch_cmd = pip + ["torch", "torchaudio"]
        if wheel.get("index_url"):
            torch_cmd += ["--index-url", wheel["index_url"]]

        _print_stderr(f"[RVC install] Installing {wheel['label']} ...")
        ok, out = _run_pip(torch_cmd)
        _print_stderr(f"[RVC install] pip output:\n{out}")
        _refresh_import_caches()
        if not ok:
            if wheel["tag"] != "cpu":
                return False, (
                    f"Failed to install {wheel['label']}:\n{out}\n\n"
                    "Scarlet did not automatically fall back to CPU, because that can hide "
                    "a broken NVIDIA/CUDA install. Fix the GPU install or rerun the installer "
                    "in CPU mode if you explicitly want CPU-only RVC."
                )
            return False, f"Failed to install torch:\n{out}"
    else:
        _print_stderr(
            "[RVC install] torch + torchaudio already installed "
            f"(torch={already.get('torch')}, torchaudio={already.get('torchaudio')}); "
            "skipping reinstall."
        )

    # --- Step 2: backend-specific extras -----------------------------------
    # Only run when we actually ran Step 1 — otherwise we risk re-pulling
    # mismatched extras on top of a torch build the user is happy with.
    if torch_needed and wheel["tag"] == "xpu":
        _print_stderr("[RVC install] Installing intel_extension_for_pytorch ...")
        ok, out = _run_pip(pip + ["intel-extension-for-pytorch"])
        _print_stderr(f"[RVC install] pip output:\n{out}")
        _refresh_import_caches()
        if not ok:
            _print_stderr(f"[RVC install] XPU extras failed (non-fatal):\n{out}")
    elif torch_needed and wheel["tag"] == "directml":
        _print_stderr("[RVC install] Installing torch-directml ...")
        ok, out = _run_pip(pip + ["torch-directml"])
        _print_stderr(f"[RVC install] pip output:\n{out}")
        _refresh_import_caches()
        if not ok:
            _print_stderr(f"[RVC install] DirectML extras failed (non-fatal):\n{out}")

    # --- Step 3: torchcodec (OPTIONAL) -------------------------------------
    # torchcodec is a nice-to-have: rvc-python 0.1.x does not import it at
    # runtime, and Windows wheel coverage is spotty.  Attempt the install
    # but treat any failure (or a silent no-op where pip exits 0 but the
    # package still isn't importable) as non-fatal and just log it.
    torchcodec_installed = False
    if _package_version("torchcodec") is None:
        _print_stderr("[RVC install] Installing torchcodec (optional) ...")
        ok, out = _run_pip(pip + ["torchcodec"])
        _print_stderr(f"[RVC install] pip output:\n{out}")
        _refresh_import_caches()
        # Verify the package actually became importable — pip can exit 0 on
        # some wheel-selection edge cases without actually installing.
        post_ver = _package_version("torchcodec")
        if not ok or post_ver is None:
            _print_stderr(
                "[RVC install] torchcodec install did not complete "
                f"(pip ok={ok}, post-install version={post_ver}).  "
                "Continuing without it — rvc-python 0.1.x does not require it."
            )
        else:
            torchcodec_installed = True
            _print_stderr(f"[RVC install] torchcodec installed ({post_ver}).")
    else:
        _print_stderr(
            f"[RVC install] torchcodec already installed ({already.get('torchcodec')}); skipping."
        )

    # --- Step 4: rvc-python ------------------------------------------------
    rvc_python_installed = False
    if _package_version("rvc_python") is None:
        _print_stderr("[RVC install] Installing rvc-python ...")
        ok, out = _run_pip(pip + ["rvc-python"])
        _print_stderr(f"[RVC install] pip output:\n{out}")
        _refresh_import_caches()
        if not ok:
            return False, f"Failed to install rvc-python:\n{out}"
        if _package_version("rvc_python") is None:
            return False, (
                "pip reported success installing rvc-python but the package "
                "is still not importable. Check the pip log above for the "
                "actual resolver decision."
            )
        rvc_python_installed = True
    else:
        _print_stderr(
            f"[RVC install] rvc-python already installed ({already.get('rvc-python')}); skipping."
        )

    # Build a message that reflects what we actually did vs. skipped.
    done_steps = []
    if torch_needed:
        done_steps.append(wheel["label"])
    if torchcodec_installed:
        done_steps.append("torchcodec")
    if rvc_python_installed:
        done_steps.append("rvc-python")
    summary = ", ".join(done_steps) if done_steps else "no packages needed installation"

    return True, (
        f"Installed: {summary}.\n"
        f"Python: {py['version']}  |  OS: {sys_info['os']} {sys_info['arch']}\n"
        f"GPUs detected: {len(gpus)}"
    )


def _run_pip(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
        combined = (r.stdout or "") + (r.stderr or "")
        return r.returncode == 0, combined
    except Exception as exc:
        return False, str(exc)


# ---------------------------------------------------------------------------
# Device resolution
# ---------------------------------------------------------------------------

def resolve_device(device_arg, primary_gpu):
    """Resolve 'auto' to the best available device string."""
    if device_arg and device_arg != "auto":
        return device_arg
    if primary_gpu is not None:
        return primary_gpu.get("device", "cpu")
    return "cpu"


# ---------------------------------------------------------------------------
# Status
# ---------------------------------------------------------------------------

def get_rvc_status():
    """Return the full RVC environment status as a dict."""
    missing          = check_dependencies()          # required only
    optional_missing = check_optional_missing()      # nice-to-have
    sys_info  = get_system_info()
    py_info   = get_python_info()
    ffmpeg    = check_ffmpeg()
    gpus      = detect_all_gpus()
    primary   = pick_primary_gpu(gpus)
    wheel     = recommend_torch_wheel(sys_info, gpus)
    versions  = package_versions()
    external  = discover_external_rvc_installs()

    models_dir = Path(__file__).parent / "models"
    models = []
    if models_dir.exists():
        for f in sorted(models_dir.glob("**/*.pth")):
            models.append(str(f.relative_to(models_dir)))

    # rvc_compatible requires: *required* deps installed AND python >= 3.9.
    # Optional deps (torchcodec) are surfaced in `optional_missing` but do
    # NOT participate in the compatibility gate — rvc-python 0.1.x runs
    # fine without them and we don't want a missing nice-to-have to block
    # conversion.  FFmpeg is similarly not required for the WAV-only flow.
    rvc_compatible = (len(missing) == 0) and py_info["is_compatible"]

    return {
        # --- v2 fields -----------------------------------------------------
        "schema_version":       STATUS_SCHEMA_VERSION,
        "system":               sys_info,
        "python":               py_info,
        "ffmpeg":               ffmpeg,
        "gpus":                 gpus,
        "package_versions":     versions,
        "recommended_install":  wheel,
        "external_installs":    external,
        "optional_missing":     optional_missing,

        # --- legacy fields (unchanged shape for old Java consumers) --------
        "dependencies_missing": missing,
        "gpu":                  legacy_gpu_dict(primary),
        "rvc_compatible":       rvc_compatible,
        "models_available":     models,
        "models_dir":           str(models_dir),
    }


# ---------------------------------------------------------------------------
# Conversion — primary path via rvc-python
# ---------------------------------------------------------------------------

def convert_rvc_python(input_path, output_path, model_path,
                       index_path=None, pitch=0, method="rmvpe",
                       index_rate=0.5, filter_radius=3, resample_sr=0,
                       rms_mix_rate=0.25, protect=0.33, device="cuda:0"):
    """
    Run voice conversion using the rvc-python library.
    Returns (success: bool, message: str).
    """
    try:
        # Must run BEFORE rvc_python is imported — rvc-python's fairseq
        # hubert checkpoint fails under PyTorch 2.6+'s new default of
        # weights_only=True. See _prepare_torch_for_rvc_checkpoints().
        _prepare_torch_for_rvc_checkpoints()

        import inspect
        # rvc-python emits progress/info prints directly to stdout during
        # model load AND inference (e.g. "overwrite preprocess and
        # configs.json", "gin_channels: 256 self.spk_embed_dim: 109",
        # "Model X.pth loaded.").  Scarlet reads our stdout as JSON, so
        # any leak breaks parsing.  Route the library's stdout to stderr
        # for the whole lifetime of RVCInference (load + infer), where
        # Scarlet's stderr drainer logs it at DEBUG level.
        with contextlib.redirect_stdout(sys.stderr):
            from rvc_python.infer import RVCInference  # type: ignore

            rvc = RVCInference(device=device)
            rvc.load_model(model_path)

            if index_path and os.path.exists(index_path):
                try:
                    if hasattr(rvc, "set_index_file"):
                        rvc.set_index_file(index_path)
                except Exception as exc:
                    _print_stderr(f"[RVC] set_index_file failed: {exc}")

            sig = inspect.signature(rvc.infer_file)
            params = sig.parameters
            # If infer_file declares **kwargs, inspect.signature still only
            # lists explicit parameters plus one VAR_KEYWORD entry; we want
            # to allow *any* candidate name in that case and let the
            # library decide.
            accepts_var_kwargs = any(
                p.kind is inspect.Parameter.VAR_KEYWORD for p in params.values()
            )
            kwargs = {}

            def set_first(names, value):
                # With VAR_KEYWORD we optimistically pass the first
                # candidate; the TypeError-retry loop below will strip any
                # name the library actually rejects at call time (e.g.
                # rvc-python's ``f0method``).
                for name in names:
                    if name in params or accepts_var_kwargs:
                        kwargs[name] = value
                        return True
                return False

            set_first(("index_path", "file_index", "index_file", "faiss_index_path"), index_path)
            set_first(("f0method", "f0_method", "method", "pitch_method"), method)
            set_first(("f0up_key", "f0_up_key", "pitch", "transpose", "key"), pitch)
            set_first(("index_rate", "index_ratio", "feature_ratio"), index_rate)
            set_first(("filter_radius",), filter_radius)
            set_first(("resample_sr", "resample_rate", "output_sr"), resample_sr)
            set_first(("rms_mix_rate", "rms_mix", "volume_envelope", "envelope_mix"), rms_mix_rate)
            set_first(("protect", "consonant_protect"), protect)

            # Drop None values — let the library fall back to its own
            # defaults rather than complaining about NoneType where a
            # number was expected.
            call_kwargs = {k: v for k, v in kwargs.items() if v is not None}

            # Robustness: rvc-python's ``infer_file`` has reshuffled its
            # kwargs between releases (e.g. ``f0method`` worked on older
            # versions but the current release raises TypeError). Rather
            # than pinning to one signature, we iteratively strip whatever
            # kwarg the library rejects and retry — so the same bridge
            # keeps working across library bumps.
            rejected = []
            # Match: "unexpected keyword argument 'X'" OR
            # "got multiple values for keyword argument 'X'"
            import re as _re
            bad_kw_re = _re.compile(
                r"(?:unexpected keyword argument|multiple values for keyword argument)\s+['\"]([^'\"]+)['\"]"
            )
            # Bound the loop so a pathological library can't spin forever.
            for _ in range(len(call_kwargs) + 1):
                try:
                    rvc.infer_file(input_path, output_path, **call_kwargs)
                    note = ""
                    if rejected:
                        note = " (stripped unsupported kwargs: " + ", ".join(rejected) + ")"
                    return True, "rvc-python conversion successful" + note
                except TypeError as te:
                    m = bad_kw_re.search(str(te))
                    if not m:
                        # Unrelated TypeError — propagate.
                        raise
                    bad = m.group(1)
                    if bad not in call_kwargs:
                        # Library rejected a kwarg we didn't set (probably
                        # a required positional surfaced via duplication).
                        # Give up.
                        raise
                    rejected.append(bad)
                    _print_stderr(
                        f"[RVC] infer_file rejected kwarg '{bad}' — retrying without it"
                    )
                    call_kwargs.pop(bad, None)

            # All kwargs stripped and it still complained — last-ditch
            # positional.
            rvc.infer_file(input_path, output_path)
            return True, (
                "rvc-python conversion successful (fallback: no kwargs accepted; "
                "rejected " + ", ".join(rejected) + ")"
            )
    except Exception as exc:
        return False, f"{type(exc).__name__}: {exc}"


# ---------------------------------------------------------------------------
# Conversion — fallback / no-model passthrough via torchaudio
# ---------------------------------------------------------------------------

def convert_passthrough(input_path, output_path, resample_sr=0, device="cpu"):
    """
    Passthrough: load the WAV, optionally resample, and write to output.
    Used when no model is specified, or as a last-resort fallback after
    rvc-python fails on both GPU and CPU.
    Returns (success: bool, message: str).
    """
    try:
        import torchaudio  # type: ignore

        waveform, sample_rate = torchaudio.load(input_path)
        # DirectML/XPU devices may not be valid torchaudio targets — guard.
        try:
            waveform = waveform.to(device)
        except Exception:
            device = "cpu"

        target_sr = resample_sr if resample_sr > 0 else sample_rate
        if target_sr != sample_rate:
            resampler = torchaudio.transforms.Resample(
                orig_freq=sample_rate, new_freq=target_sr
            ).to(device)
            waveform = resampler(waveform)

        torchaudio.save(output_path, waveform.cpu(), target_sr)
        note = (
            f", resampled {sample_rate} → {target_sr} Hz"
            if target_sr != sample_rate else ""
        )
        return True, f"Passthrough (no RVC model — audio copied{note})"
    except Exception as exc:
        return False, f"{type(exc).__name__}: {exc}"


# ---------------------------------------------------------------------------
# Path resolution helpers
# ---------------------------------------------------------------------------

def _candidate_models_dirs(extra_models_dir=None):
    """
    Return an ordered, de-duplicated list of directories we will search
    for models and indexes. Callers can supply ``extra_models_dir`` (the
    absolute path Scarlet uses via ``RvcService.getModelsDir()``) so the
    bridge finds files even when the Python script lives in a different
    subtree than the user's models.
    """
    cands = []
    seen = set()
    def _add(p):
        if not p:
            return
        try:
            ap = Path(p).resolve()
        except Exception:
            return
        key = str(ap).lower() if os.name == "nt" else str(ap)
        if key in seen:
            return
        seen.add(key)
        cands.append(ap)

    _add(extra_models_dir)
    # Script-adjacent models dir (portable layout)
    _add(Path(__file__).parent / "models")
    # One level up, in case the bridge was extracted to <root>/tts/rvc/
    # but the user's models are at <root>/rvc/models/.
    _add(Path(__file__).parent.parent / "rvc" / "models")
    _add(Path(__file__).parent.parent / "models")
    return cands


def resolve_model_path(model_arg, extra_models_dir=None):
    """
    Resolve a model path:
      1. As-is (absolute or relative to CWD)
      2. Under any of the known models dirs (Scarlet's explicit one first,
         then the script-adjacent/legacy fallbacks).
    Returns (resolved_str_or_None, error_str_or_None).
    """
    if not model_arg:
        return None, None
    if os.path.exists(model_arg):
        return model_arg, None
    tried = []
    for d in _candidate_models_dirs(extra_models_dir):
        candidate = d / model_arg
        tried.append(str(candidate))
        if candidate.exists():
            return str(candidate), None
    # Also try a recursive scan in case the user organised models into
    # per-voice subfolders (e.g. ``models/Blake/Blake.pth``).
    target = Path(model_arg).name
    for d in _candidate_models_dirs(extra_models_dir):
        if not d.is_dir():
            continue
        try:
            for f in d.rglob(target):
                if f.is_file():
                    return str(f), None
        except Exception:
            continue
    return None, (
        "Model file not found: " + model_arg +
        " (also checked: " + ", ".join(tried) + ")"
    )


def resolve_index_path(index_arg, extra_models_dir=None):
    """Same resolution logic for the optional .index file.  Never hard-fails."""
    if not index_arg:
        return None, None
    if os.path.exists(index_arg):
        return index_arg, None
    tried = []
    for d in _candidate_models_dirs(extra_models_dir):
        candidate = d / index_arg
        tried.append(str(candidate))
        if candidate.exists():
            return str(candidate), None
    # Recursive fallback — the same retrieval index can live in a
    # subfolder of the models dir.
    target = Path(index_arg).name
    for d in _candidate_models_dirs(extra_models_dir):
        if not d.is_dir():
            continue
        try:
            for f in d.rglob(target):
                if f.is_file():
                    return str(f), None
        except Exception:
            continue
    _print_stderr(
        "[RVC] Index file not found: " + index_arg +
        " — proceeding without it (checked: " + ", ".join(tried) + ")"
    )
    return None, None


def auto_pair_index(model_path, extra_search_dirs=None):
    """
    Given a resolved .pth model path, locate an .index file that likely
    belongs to it. This is what makes the "Add Model" UI workable: the
    user drops a .pth in and we find its retrieval index automatically,
    regardless of whether they named it after the stem, kept RVC-WebUI's
    ``added_IVF*_Flat_nprobe_1_<name>_v2.index`` convention, or stored it
    in a sibling folder.

    Lookup strategy (first hit wins):
      1. Sibling with the exact same stem:       ``<model>.index``
      2. Sibling whose name contains the stem (case-insensitive) — this
         catches the ``added_..._<stem>_v2.index`` convention as well as
         any user-renamed variant.
      3. Any sibling matching ``added_*.index``   (standard RVC output)
      4. Any other sibling ``*.index``
      5. Steps 2–4 repeated recursively under ``<model_dir>``
      6. Steps 2–4 repeated under each ``extra_search_dirs`` entry (the
         Java side passes Scarlet's models directory so we find indexes
         even when the bridge script lives somewhere else on disk).

    ``extra_search_dirs`` is an iterable of path-like objects; ``None``
    and missing dirs are silently ignored so callers can pass optimistic
    candidates.

    Returns a path string or ``None``.
    """
    if not model_path:
        return None
    try:
        mp = Path(model_path)
        if not mp.exists():
            return None
        stem = mp.stem
        lower_stem = stem.lower()

        def _pick(files):
            """Apply the same stem/added_* preference order to a list."""
            if not files:
                return None
            # Prefer names containing the model stem
            matching = [f for f in files if lower_stem and lower_stem in f.name.lower()]
            if matching:
                # Among those, prefer the RVC-WebUI convention
                added = [f for f in matching if f.name.startswith("added_")]
                return str((added or matching)[0])
            # Otherwise: added_* anywhere, else any .index
            added = [f for f in files if f.name.startswith("added_")]
            return str((added or files)[0])

        # 1. exact-stem sibling — fast path
        same_stem = mp.with_suffix(".index")
        if same_stem.exists():
            return str(same_stem)

        # 2-4. flat sibling scan
        flat = sorted(mp.parent.glob("*.index"))
        hit = _pick(flat)
        if hit:
            return hit

        # 5. recursive under model dir
        rec = sorted(mp.parent.rglob("*.index"))
        hit = _pick(rec)
        if hit:
            return hit

        # 6. extra search dirs (Scarlet's models dir etc.)
        for extra in (extra_search_dirs or ()):
            if not extra:
                continue
            try:
                ed = Path(extra)
                if not ed.is_dir():
                    continue
                files = sorted(ed.rglob("*.index"))
                hit = _pick(files)
                if hit:
                    return hit
            except Exception as exc:
                _print_stderr(f"[RVC] auto_pair_index scan of {extra} failed: {exc}")
    except Exception as exc:
        _print_stderr(f"[RVC] auto_pair_index failed for {model_path}: {exc}")
    return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="RVC Bridge for Scarlet TTS",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python rvc_bridge.py --status
  python rvc_bridge.py --install
  python rvc_bridge.py --install --device cpu
  python rvc_bridge.py -i tts.wav -o rvc.wav -m voice.pth
  python rvc_bridge.py -i tts.wav -o rvc.wav -m voice.pth --pitch 2 --method rmvpe
""",
    )

    # ---- mode flags -------------------------------------------------------
    parser.add_argument(
        "--status", action="store_true",
        help="Print RVC environment status as JSON and exit",
    )
    parser.add_argument(
        "--install", action="store_true",
        help="Install / upgrade all required Python packages and exit",
    )

    # ---- I/O --------------------------------------------------------------
    parser.add_argument("-i", "--input",  help="Input WAV file path")
    parser.add_argument("-o", "--output", help="Output WAV file path")
    parser.add_argument("-m", "--model",  help="Path to RVC model (.pth file)")
    parser.add_argument(
        "--index",
        help="Path to RVC index file (.index) — optional, improves timbre matching",
    )
    parser.add_argument(
        "--models-dir",
        dest="models_dir",
        default=None,
        help=(
            "Absolute path to the directory containing RVC models. Scarlet "
            "passes its own data-dir location here so relative --model / "
            "--index values resolve correctly even when the bridge script "
            "lives elsewhere on disk."
        ),
    )

    # ---- conversion params ------------------------------------------------
    parser.add_argument(
        "--pitch", type=int, default=0,
        help="Pitch adjustment in semitones, -24 to +24 (default: 0)",
    )
    parser.add_argument(
        "--method", default="rmvpe",
        choices=["pm", "harvest", "crepe", "rmvpe"],
        help="Pitch extraction method (default: rmvpe)",
    )
    parser.add_argument(
        "--index-rate", type=float, default=0.5,
        help="Feature search ratio 0.0–1.0 (default: 0.5)",
    )
    parser.add_argument(
        "--filter-radius", type=int, default=3,
        help="Median filtering radius 0–7 (default: 3)",
    )
    parser.add_argument(
        "--resample-sr", type=int, default=0,
        help="Output sample rate in Hz; 0 = keep original (default: 0)",
    )
    parser.add_argument(
        "--rms-mix-rate", type=float, default=0.25,
        help="Volume envelope mix rate 0.0–1.0 (default: 0.25)",
    )
    parser.add_argument(
        "--protect", type=float, default=0.33,
        help="Voiceless consonant protection 0.0–0.5 (default: 0.33)",
    )
    parser.add_argument(
        "--device", default="auto",
        help="Inference device: auto | cpu | cuda:0 | xpu:0 | privateuseone:0 … (default: auto)",
    )
    parser.add_argument(
        "--timeout", type=int, default=120,
        help="Conversion timeout hint in seconds — informational only (default: 120)",
    )

    args = parser.parse_args()

    # ---------------------------------------------------------------- status
    if args.status:
        print(json.dumps(get_rvc_status(), indent=2))
        return 0

    # --------------------------------------------------------------- install
    if args.install:
        success, message = install_dependencies(device_hint=args.device)
        print(json.dumps({
            "success": success,
            "message": message,
            "status":  get_rvc_status(),
        }, indent=2))
        return 0 if success else 1

    # -------------------------------------------------------------- convert
    if not args.input or not args.output:
        parser.error("--input and --output are required for conversion")

    if not os.path.exists(args.input):
        print(json.dumps({
            "success": False,
            "error":   f"Input file not found: {args.input}",
        }))
        return 1

    # Guard: refuse to attempt conversion if Python is out of supported range.
    py = get_python_info()
    if not py["is_compatible"]:
        print(json.dumps({
            "success": False,
            "error":   py.get("incompatible_reason")
                       or f"Python {py['version']} is outside the supported "
                          f"range {py['min_required']}\u2013{py['max_supported']}",
            "python":  py,
        }))
        return 2

    missing = check_dependencies()
    if missing:
        print(json.dumps({
            "success": False,
            "error": (
                f"Missing Python dependencies: {missing}. "
                "Run:  python rvc_bridge.py --install"
            ),
            "dependencies_missing": missing,
        }))
        return 2

    gpus       = detect_all_gpus()
    primary    = pick_primary_gpu(gpus)
    device     = resolve_device(args.device, primary)
    models_dir_hint = getattr(args, "models_dir", None)
    model_path, model_err = resolve_model_path(args.model, models_dir_hint)
    if model_err:
        print(json.dumps({"success": False, "error": model_err}))
        return 1

    index_path, _ = resolve_index_path(args.index, models_dir_hint)

    # If we still have no index, do a smart auto-pair (sibling .index,
    # stem-matching .index, added_*.index, recursive scan, plus the
    # caller-supplied models dir). This handles the common case where
    # the user's .index file uses RVC-WebUI's naming convention rather
    # than sharing the .pth's basename.
    if not index_path and model_path:
        auto_index = auto_pair_index(
            model_path,
            extra_search_dirs=[models_dir_hint] if models_dir_hint else None,
        )
        if auto_index:
            _print_stderr(f"[RVC] auto-paired index: {auto_index}")
            index_path = auto_index

    try:
        if model_path:
            # Primary: rvc-python on preferred device
            success, message = convert_rvc_python(
                args.input, args.output,
                model_path, index_path,
                pitch=args.pitch,
                method=args.method,
                index_rate=args.index_rate,
                filter_radius=args.filter_radius,
                resample_sr=args.resample_sr,
                rms_mix_rate=args.rms_mix_rate,
                protect=args.protect,
                device=device,
            )

            # Any-accelerator→CPU retry on failure.
            if not success and device != "cpu":
                _print_stderr(
                    f"[RVC] {device} inference failed ({message}), retrying on CPU ..."
                )
                success, message = convert_rvc_python(
                    args.input, args.output,
                    model_path, index_path,
                    pitch=args.pitch,
                    method=args.method,
                    index_rate=args.index_rate,
                    filter_radius=args.filter_radius,
                    resample_sr=args.resample_sr,
                    rms_mix_rate=args.rms_mix_rate,
                    protect=args.protect,
                    device="cpu",
                )
        else:
            # No model specified — passthrough (resample-only)
            success, message = convert_passthrough(
                args.input, args.output,
                resample_sr=args.resample_sr,
                device=device,
            )

        if success:
            print(json.dumps({
                "success":     True,
                "output":      args.output,
                "device_used": device,
                "message":     message,
            }))
            return 0
        else:
            print(json.dumps({
                "success":     False,
                "error":       message,
                "device_used": device,
            }))
            return 1

    except Exception as exc:
        print(json.dumps({
            "success":   False,
            "error":     f"{type(exc).__name__}: {exc}",
            "traceback": traceback.format_exc(),
        }))
        return 1


if __name__ == "__main__":
    sys.exit(main())