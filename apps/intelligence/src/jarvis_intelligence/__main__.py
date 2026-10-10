"""Loopback-only explicit mock or mutually authenticated local inference launcher."""
import argparse
import os
import stat
import ssl
from pathlib import Path

import uvicorn
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from .app import Settings, create_app
from .local_runtime import LocalConfig
from .tls import MutualTLSProtocol


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-loopback-mock-without-mtls", action="store_true")
    parser.add_argument("--core-public-key", type=Path, required=True)
    parser.add_argument("--core-key-id", required=True)
    parser.add_argument("--cache-key-file", type=Path, required=True)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--port", type=int, default=9443)
    parser.add_argument("--local-config", type=Path)
    parser.add_argument("--tls-cert", type=Path)
    parser.add_argument("--tls-key", type=Path)
    parser.add_argument("--tls-ca", type=Path)
    parser.add_argument("--core-cert-sha256")
    args = parser.parse_args()
    if args.local_config:
        if args.allow_loopback_mock_without_mtls or not all((args.tls_cert, args.tls_key, args.tls_ca, args.core_cert_sha256)):
            parser.error("local inference requires mTLS certificate, key, CA and Core certificate fingerprint")
    elif not args.allow_loopback_mock_without_mtls:
        parser.error("select explicit mock or authenticated local mode")
    # Cache key must be an owner-only regular file, not a link or environment value.
    fd = os.open(args.cache_key_file, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as file:
        info = os.fstat(file.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.getuid():
            parser.error("cache key must be an owner-only regular file")
        cache_key = file.read(33)
    if len(cache_key) != 32:
        parser.error("cache key must contain exactly 32 random bytes")
    key = load_pem_public_key(args.core_public_key.read_bytes())
    if not isinstance(key, Ed25519PublicKey):
        parser.error("Core public key must be Ed25519")
    local = LocalConfig.from_file(args.local_config) if args.local_config else None
    app = create_app(Settings(args.ledger.resolve(), cache_key, {args.core_key_id: key},
                              allow_loopback_mock_without_mtls=local is None,
                              core_certificate_fingerprints=frozenset([args.core_cert_sha256]) if local else frozenset(),
                              mode="local_mlx" if local else "mock", local_config=local))
    # No proxy headers, public bind, autoreload, access logging or extra workers.
    tls = {}
    if local:
        tls = {"ssl_certfile": str(args.tls_cert), "ssl_keyfile": str(args.tls_key),
               "ssl_ca_certs": str(args.tls_ca), "ssl_cert_reqs": ssl.CERT_REQUIRED,
               "http": MutualTLSProtocol}
    uvicorn.run(app, host="127.0.0.1", port=args.port, proxy_headers=False, access_log=False, **tls)


if __name__ == "__main__":
    main()
