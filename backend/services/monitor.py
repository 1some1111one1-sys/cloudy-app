"""Host and device resource monitoring (no third-party packages)."""
from __future__ import annotations

import os
import shutil


def host_resources(path: str = "/") -> dict:
    mem_total = mem_avail = 0
    try:
        with open("/proc/meminfo") as fh:
            for line in fh:
                if line.startswith("MemTotal:"):
                    mem_total = int(line.split()[1]) // 1024
                elif line.startswith("MemAvailable:"):
                    mem_avail = int(line.split()[1]) // 1024
    except OSError:
        pass
    du = shutil.disk_usage(path if os.path.isdir(path) else "/")
    cores = os.cpu_count() or 1
    load = os.getloadavg()[0] if hasattr(os, "getloadavg") else 0.0
    return {
        "cpu_cores": cores,
        "cpu_percent": round(min(100.0, load / cores * 100), 1),
        "ram_total_mb": mem_total,
        "ram_used_mb": max(mem_total - mem_avail, 0),
        "disk_total_gb": round(du.total / 1e9, 1),
        "disk_used_gb": round(du.used / 1e9, 1),
        "kvm_available": os.path.exists("/dev/kvm"),
    }
