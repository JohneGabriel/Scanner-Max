#!/usr/bin/env python3
"""Registro de vendas e aparelhos (um JSON por licença em registro/<id>.json).

Usado pelas automações:
  python3 registro.py ativar   <dir-registro> <dir-respostas>   (evento do app: ativar/liberar)
  python3 registro.py venda    <dir-registro>                   (Nova venda: gera licença e mensagem)
  python3 registro.py gerenciar <dir-registro> <ação> <pedido-ou-id>

Nunca imprime licenças, ids ou aparelhos no log: o log pode ser visto por quem tem acesso ao repositório.
"""
import hashlib
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import licenca as L  # noqa: E402

IDLE_DAYS = 60          # aparelho sem abrir o app por esse tempo perde a vaga
REPLY_TTL = 2 * 86400   # respostas antigas são apagadas
SITE = "https://johnegabriel.github.io/scannermax/"
DEV_RE = re.compile(r"^[0-9a-f]{24}$")
NONCE_RE = re.compile(r"^[0-9a-f]{24}$")


def _path(reg, lid):
    return os.path.join(reg, "registro", f"{lid}.json")


def load(reg, lid):
    try:
        with open(_path(reg, lid)) as f:
            return json.load(f)
    except FileNotFoundError:
        return None


def save(reg, lid, data):
    os.makedirs(os.path.join(reg, "registro"), exist_ok=True)
    with open(_path(reg, lid), "w") as f:
        json.dump(data, f, indent=1, ensure_ascii=False, sort_keys=True)


def prune(data, now):
    limit = now - IDLE_DAYS * 86400
    devs = data.setdefault("aparelhos", {})
    for d in [d for d, v in devs.items() if v.get("visto", 0) < limit]:
        del devs[d]


def out(key, value):
    """Saída para os próximos passos do GitHub Actions."""
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as f:
            if "\n" in value:
                f.write(f"{key}<<FIM\n{value}\nFIM\n")
            else:
                f.write(f"{key}={value}\n")


# ------------------------------------------------------------------ pedido do app

def handle_app(reg, replies, event, payload, now=None):
    """Processa ativar/liberar. Devolve o texto do recibo (ou None se o pedido for inválido)."""
    now = int(now or time.time())
    lic = L.parse_license(str(payload.get("l", "")))
    dev = str(payload.get("d", ""))
    nonce = str(payload.get("n", ""))
    if not lic or not DEV_RE.match(dev) or not NONCE_RE.match(nonce) or event not in ("ativar", "liberar"):
        print("Pedido ignorado (inválido)")
        return None
    lid = lic["id"]
    data = load(reg, lid) or {"pedido": lic["pedido"], "max": lic["max"], "emitida": lic["emitida"],
                              "licenca": payload["l"], "aparelhos": {}, "origem": "ativacao"}
    prune(data, now)
    devs = data["aparelhos"]
    mx = int(data.get("max", lic["max"]))
    if data.get("bloqueada"):
        rec = L.receipt(lid, dev, False, "bloqueada", len(devs), mx, now)
    elif event == "liberar":
        devs.pop(dev, None)
        rec = L.receipt(lid, dev, False, "liberado", len(devs), mx, now)
    elif dev in devs:
        devs[dev]["visto"] = now
        rec = L.receipt(lid, dev, True, "ok", len(devs), mx, now)
    elif len(devs) < mx:
        devs[dev] = {"primeiro": now, "visto": now}
        rec = L.receipt(lid, dev, True, "ok", len(devs), mx, now)
    else:
        rec = L.receipt(lid, dev, False, "limite", len(devs), mx, now)
    save(reg, lid, data)

    os.makedirs(replies, exist_ok=True)
    name = hashlib.sha256(f"{lid}:{dev}:{nonce}".encode()).hexdigest()[:32]
    with open(os.path.join(replies, f"{name}.txt"), "w") as f:
        f.write(rec)
    # limpa respostas antigas
    for fn in os.listdir(replies):
        p = os.path.join(replies, fn)
        try:
            at = int(open(p).read().split(".")[7])
        except Exception:
            at = 0
        if now - at > REPLY_TTL:
            os.remove(p)
    print(f"Pedido '{event}' processado: {rec.split('.')[4]}")
    return rec


# ------------------------------------------------------------------ vendedor

def message(lic_text, max_devices):
    link = f"{SITE}a/#{lic_text}"
    return (
        "Olá! Obrigado pela compra do Scanner Max 🚗🔧\n\n"
        f"Seu link pessoal de instalação:\n{link}\n\n"
        "1. Abra o link no celular e toque em \"Baixar o app\".\n"
        "2. Instale. Se o Android pedir, permita instalar apps desta fonte.\n"
        "3. Abra o Scanner Max: a ativação é automática. Se não ativar, volte ao link e toque em \"Ativar no app\".\n\n"
        f"A licença vale para até {max_devices} aparelhos. Guarde este link: ele serve para instalar de novo quando precisar.\n"
        "Qualquer dúvida, é só chamar por aqui!"
    )


def new_sale(reg, pedido, max_devices):
    lic = L.new_license(pedido, max_devices)
    save(reg, lic["id"], {"pedido": lic["pedido"], "max": max_devices, "emitida": lic["emitida"],
                          "licenca": lic["text"], "aparelhos": {}, "criada": int(time.time())})
    final = lic["pedido"][-4:] if lic["pedido"] else lic["id"][:6]
    out("titulo", f"Venda {final} · {time.strftime('%d/%m/%Y')}")
    out("mensagem", message(lic["text"], max_devices))
    print(f"Licença criada para o pedido final {final}")
    return lic


def find(reg, query):
    q = re.sub(r"[^0-9a-f]", "", query.lower())
    if not q:
        return []
    found = []
    d = os.path.join(reg, "registro")
    for fn in sorted(os.listdir(d)) if os.path.isdir(d) else []:
        lid = fn[:-5]
        data = load(reg, lid)
        if lid.startswith(q) or (len(q) >= 4 and str(data.get("pedido", "")).endswith(q)):
            found.append((lid, data))
    return found


def manage(reg, action, query):
    found = find(reg, query)
    if len(found) != 1:
        print(f"::error::{'Nenhuma venda' if not found else f'{len(found)} vendas'} com esse pedido. "
              "Use mais dígitos do pedido ou o início do código da licença.")
        sys.exit(1)
    lid, data = found[0]
    final = str(data.get("pedido", ""))[-4:] or lid[:6]
    if action == "liberar aparelhos":
        n = len(data.get("aparelhos", {}))
        data["aparelhos"] = {}
        summary = f"Venda {final}: {n} aparelho(s) liberado(s)."
    elif action == "bloquear":
        data["bloqueada"] = True
        summary = f"Venda {final}: licença bloqueada."
    elif action == "desbloquear":
        data["bloqueada"] = False
        summary = f"Venda {final}: licença desbloqueada."
    elif action == "reenviar mensagem":
        summary = f"Venda {final}: mensagem gerada de novo."
    else:
        print(f"::error::Ação desconhecida: {action}")
        sys.exit(1)
    save(reg, lid, data)
    out("titulo", f"Venda {final} · {action}")
    body = summary
    if action == "reenviar mensagem":
        body += "\n\n" + message(data["licenca"], data.get("max", 3))
    else:
        devs = data.get("aparelhos", {})
        body += f"\n\nAparelhos em uso agora: {len(devs)} de {data.get('max', 3)}."
    out("mensagem", body)
    print(summary)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "ativar":
        with open(os.environ["GITHUB_EVENT_PATH"]) as f:
            ev = json.load(f)
        handle_app(sys.argv[2], sys.argv[3], ev.get("action", ""), ev.get("client_payload") or {})
    elif cmd == "venda":
        new_sale(sys.argv[2], os.environ.get("PEDIDO", ""), int(os.environ.get("APARELHOS", "3") or 3))
    elif cmd == "gerenciar":
        manage(sys.argv[2], os.environ.get("ACAO", ""), os.environ.get("PEDIDO", ""))
    else:
        print(__doc__)
