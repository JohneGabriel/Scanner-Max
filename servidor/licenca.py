#!/usr/bin/env python3
"""Licenças do Scanner Max (lado do servidor): gera licenças e assina recibos de ativação.

Mesmo formato que o app confere (app/src/main/java/br/obd/app/License.kt):
  licença: SMX1.<id>.<pedido>.<AAAAMMDD>.<máx>.<assinatura>
  recibo:  R1.<id>.<aparelho>.<1|0>.<motivo>.<usados>.<máx>.<epoch s>.<assinatura>
Assinatura: ECDSA P-256 + SHA-256, DER, base64 url-safe sem '='.

A chave privada vem da variável de ambiente LICENSE_KEY (PEM), nunca de arquivo no repositório.
"""
import base64
import datetime
import os
import re
import secrets
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def _key():
    pem = os.environ.get("LICENSE_KEY", "").strip()
    if not pem:
        sys.exit("LICENSE_KEY não definida")
    return serialization.load_pem_private_key(pem.encode(), password=None)


def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def _unb64(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def sign(msg: str, key=None) -> str:
    return _b64((key or _key()).sign(msg.encode(), ec.ECDSA(hashes.SHA256())))


def verify(msg: str, sig: str, key=None) -> bool:
    try:
        (key or _key()).public_key().verify(_unb64(sig), msg.encode(), ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


def new_license(pedido: str = "", max_devices: int = 3, key=None) -> dict:
    pedido = re.sub(r"\D", "", pedido)[:24]
    lid = secrets.token_hex(10)
    day = datetime.date.today().strftime("%Y%m%d")
    payload = f"SMX1|{lid}|{pedido}|{day}|{max_devices}"
    sig = sign(payload, key)
    return {"id": lid, "pedido": pedido, "emitida": day, "max": max_devices,
            "text": f"SMX1.{lid}.{pedido}.{day}.{max_devices}.{sig}"}


LIC_RE = re.compile(r"^SMX1\.([0-9a-f]{16,40})\.([0-9]{0,24})\.([0-9]{8})\.([0-9]{1,2})\.([A-Za-z0-9_-]{40,200})$")


def parse_license(text: str, key=None):
    """Devolve dict da licença se o texto e a assinatura forem válidos; senão None."""
    m = LIC_RE.match((text or "").strip())
    if not m:
        return None
    lid, pedido, day, mx, sig = m.groups()
    if not verify(f"SMX1|{lid}|{pedido}|{day}|{mx}", sig, key):
        return None
    return {"id": lid, "pedido": pedido, "emitida": day, "max": int(mx)}


def receipt(lid: str, device: str, ok: bool, motivo: str, used: int, mx: int, at: int | None = None, key=None) -> str:
    at = int(at if at is not None else time.time())
    payload = f"R1|{lid}|{device}|{1 if ok else 0}|{motivo}|{used}|{mx}|{at}"
    return f"R1.{lid}.{device}.{1 if ok else 0}.{motivo}.{used}.{mx}.{at}.{sign(payload, key)}"


if __name__ == "__main__":
    # uso local: LICENSE_KEY="$(cat chave.pem)" python3 licenca.py nova 2000012345674321
    if len(sys.argv) >= 2 and sys.argv[1] == "nova":
        print(new_license(sys.argv[2] if len(sys.argv) > 2 else "")["text"])
    elif len(sys.argv) >= 2 and sys.argv[1] == "conferir":
        print(parse_license(sys.argv[2]))
    else:
        print(__doc__)
