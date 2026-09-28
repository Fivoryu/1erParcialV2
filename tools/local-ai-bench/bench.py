#!/usr/bin/env python3
"""Arnes de medicion de latencia de IA local.

Mide lo que decide si la IA local es usable en este producto: cuanto tarda una
respuesta simple y de que depende esa latencia. No es una suite de pruebas: mide
un servicio externo (Docker Model Runner) y por eso no corre en el `pytest`
habitual.

Uso, desde la raiz del repositorio:

    python tools/local-ai-bench/bench.py

Requiere Docker Model Runner con un modelo ya descargado y el TCP habilitado:

    docker desktop enable model-runner --tcp=12434

El arnes no descarga modelos y no cambia su configuracion: solo mide.
"""

from __future__ import annotations

import json
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request

ENDPOINT = "http://localhost:12434/engines/v1/chat/completions"
SIMPLE_PROMPT = "Crea una clase Libro con titulo y anio. Responde solo el JSON, sin markdown."
MINIMAL_PROMPT = "Responde solo: {}"
LONG_PROMPT = "Escribe un JSON con una lista de 40 nombres de paises."


def model_id() -> str:
    """El nombre sale del listado de Docker: escribirlo a mano ya produjo un
    comando que apuntaba a un modelo inexistente por una mayuscula."""
    completed = subprocess.run(
        ["docker", "model", "list"], capture_output=True, text=True, timeout=120
    )
    for line in completed.stdout.splitlines()[1:]:
        name = line.split()[0] if line.strip() else ""
        if name and name.lower() != "model":
            return name
    raise RuntimeError("no hay ningun modelo descargado en Docker Model Runner")


def complete(model: str, prompt: str, max_tokens: int, thinking: bool) -> tuple[dict, float, str]:
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "temperature": 0.1,
        "stream": False,
        "response_format": {"type": "json_object"},
        "chat_template_kwargs": {"enable_thinking": thinking},
    }
    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=900) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        # El model runner devuelve el motivo real en el cuerpo (por ejemplo un
        # assert de llama.cpp). Morir con un traceback lo pierde.
        detail = error.read().decode("utf-8", errors="replace")[:400]
        raise RuntimeError(f"el model runner respondio HTTP {error.code}: {detail}") from None
    return body, time.perf_counter() - started, json.dumps(payload)


def measure(model: str, prompt: str, max_tokens: int, thinking: bool, runs: int) -> dict:
    latencies = []
    completion_tokens = 0
    reasoning_chars = 0
    content = ""
    for _ in range(runs):
        body, elapsed, _ = complete(model, prompt, max_tokens, thinking)
        message = body["choices"][0]["message"]
        latencies.append(elapsed)
        completion_tokens = body.get("usage", {}).get("completion_tokens", 0)
        reasoning_chars = len(message.get("reasoning_content") or "")
        content = message.get("content") or ""
    return {
        "median": statistics.median(latencies),
        "min": min(latencies),
        "max": max(latencies),
        "completion_tokens": completion_tokens,
        "reasoning_chars": reasoning_chars,
        "content": content,
        "is_json": _is_json(content),
    }


def _is_json(text: str) -> bool:
    """Cerco de markdown: en la medicion el modelo igual envuelve el JSON en
    ```json aunque se pida json_object, asi que esto mide tambien ese defecto."""
    try:
        json.loads(text)
        return True
    except Exception:
        return False


def main() -> int:
    try:
        model = model_id()
    except Exception as error:
        print(f"no se puede medir: {error}")
        return 1

    print(f"modelo: {model}")
    print("calentando con una peticion; el modelo puede estar ya cargado de una corrida anterior,")
    print("asi que este numero NO mide por si solo el tiempo de carga...")
    try:
        _, cold, _ = complete(model, MINIMAL_PROMPT, 8, thinking=False)
    except RuntimeError as error:
        print(f"no se puede medir: {error}")
        return 1
    print(f"  primera peticion de esta corrida: {cold:.2f} s\n")

    cases = [
        ("piso: salida minima, sin razonamiento", MINIMAL_PROMPT, 12, False, 3),
        ("respuesta simple, sin razonamiento", SIMPLE_PROMPT, 60, False, 5),
        ("respuesta simple, CON razonamiento", SIMPLE_PROMPT, 120, True, 3),
        ("salida larga, sin razonamiento", LONG_PROMPT, 300, False, 2),
    ]

    print(f"{'caso':40} {'mediana':>8} {'min':>6} {'max':>6} {'tok':>5} {'razon':>6}  JSON?")
    results = []
    for label, prompt, max_tokens, thinking, runs in cases:
        try:
            data = measure(model, prompt, max_tokens, thinking, runs)
        except RuntimeError as error:
            print(f"{label:40} fallo: {error}")
            continue
        results.append((label, data))
        print(
            f"{label:40} {data['median']:7.2f}s {data['min']:5.2f}s {data['max']:5.2f}s"
            f" {data['completion_tokens']:>5} {data['reasoning_chars']:>6}  {'si' if data['is_json'] else 'NO'}"
        )

    long_case = next((d for label, d in results if "larga" in label), None)
    if long_case:
        floor = next((d["median"] for label, d in results if "piso" in label), 0.0)
        decode = long_case["completion_tokens"] / max(long_case["median"] - floor, 0.01)
        print(f"\ndecode estimado: {decode:.1f} tok/s (descontando el piso de {floor:.2f} s)")
        print(f"modelo de latencia: {floor:.1f} s + tokens_salida / {decode:.0f}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
