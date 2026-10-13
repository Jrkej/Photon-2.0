"""Production entry point: python -m photon.imageserver (settings from PHOTON_* environment variables)."""

import os

import uvicorn

if __name__ == "__main__":
    uvicorn.run(
        "photon.imageserver.app:create_app",
        factory=True,
        host=os.environ.get("HOST", "0.0.0.0"),
        port=int(os.environ.get("PORT", "8081")),
        proxy_headers=True,
        forwarded_allow_ips=os.environ.get("FORWARDED_ALLOW_IPS", "127.0.0.1"),
        log_level=os.environ.get("LOG_LEVEL", "info"),
        access_log=False,
        timeout_keep_alive=130,  # longer than the proxy's upstream keep-alive (Caddy: 2 min)
    )
