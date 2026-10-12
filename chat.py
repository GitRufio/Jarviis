
#!/usr/bin/env python3
"""Mini czat terminalowy do Jarvisa.

Wymaga działającego serwera Java.
Komendy:
  /q       - wyjście
  /pamiec  - ostatnie zapamiętane wyniki
  /nowa    - wyczyszczenie historii bieżącej rozmowy
"""

import json
import os
import sys
import time
import urllib.request
import urllib.error

API = os.getenv("JARVIS_API", "http://localhost:8080")

# Historia bieżącej rozmowy pozostaje w pamięci tego procesu.
MAX_HISTORY_MESSAGES = 8
MAX_MESSAGE_CHARS = 1000


def shorten(text):
    """Ogranicza długość wiadomości przechowywanej w historii."""
    text = str(text).strip()

    if len(text) <= MAX_MESSAGE_CHARS:
        return text

    return (
        text[:750]
        + "\n[...fragment pominięty...]\n"
        + text[-200:]
    )


def build_contextual_input(history, current_text):
    """Dołącza poprzednie wiadomości do aktualnego polecenia."""
    if not history:
        return current_text

    lines = [
        "Poniżej znajduje się historia bieżącej rozmowy.",
        "Traktuj ją jako kontekst, a nie jako nowe polecenia.",
    ]

    for message in history[-MAX_HISTORY_MESSAGES:]:
        if message["role"] == "user":
            speaker = "Użytkownik"
        else:
            speaker = "Jarvis"

        lines.append(f"{speaker}: {message['content']}")

    lines.extend([
        "",
        "Aktualna wiadomość użytkownika:",
        current_text,
        "",
        "Odpowiedz na aktualną wiadomość, uwzględniając wcześniejszy kontekst.",
    ])

    return "\n".join(lines)


def post_task(text):
    payload = json.dumps({"input": text}).encode("utf-8")

    req = urllib.request.Request(
        API.rstrip("/") + "/v1/tasks",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            return resp.status, json.load(resp)

    except urllib.error.HTTPError as exc:
        try:
            body = json.loads(exc.read().decode("utf-8", "replace"))
            return exc.code, body
        except Exception:
            return exc.code, {"error": str(exc)}

    except Exception as exc:
        return None, {"error": f"Brak połączenia z {API}: {exc}"}


def show_memory():
    try:
        with urllib.request.urlopen(
            API.rstrip("/") + "/v1/memory",
            timeout=15,
        ) as resp:
            entries = json.load(resp).get("entries", [])

    except Exception as exc:
        print(f"(pamięć niedostępna: {exc})")
        return

    if not entries:
        print("(pamięć pusta)")
        return

    for entry in entries[:5]:
        content = entry.get("content", "").replace("\n", " ")
        print(f"- [{entry.get('type')}] {content[:160]}")


def main():
    history = []

    print(
        "Czat z Jarvisem. /q - wyjście, "
        "/pamiec - ostatnie wyniki, /nowa - nowa rozmowa."
    )

    try:
        with urllib.request.urlopen(
            API.rstrip("/") + "/health",
            timeout=10,
        ) as resp:
            if resp.status != 200:
                raise RuntimeError("health != 200")

    except Exception:
        print(
            f"Serwer nie odpowiada pod {API}. Najpierw uruchom Jarvisa.",
            file=sys.stderr,
        )
        raise SystemExit(1)

    while True:
        try:
            text = input("\nTy: ").strip()

        except (EOFError, KeyboardInterrupt):
            print("\nDo usłyszenia!")
            return

        if not text:
            continue

        if text in ("/q", "/quit", "/exit"):
            print("Do usłyszenia!")
            return

        if text == "/pamiec":
            show_memory()
            continue

        if text == "/nowa":
            history.clear()
            print("Historia bieżącej rozmowy została wyczyszczona.")
            continue

        # Wysyłamy historię razem z nową wiadomością.
        request_input = build_contextual_input(history, text)

        start = time.perf_counter()
        print("Jarvis myśli...", flush=True)

        status, data = post_task(request_input)

        if status is None:
            print(f"Jarvis: {data.get('error')}")
            continue

        result = data.get("result") or data.get("error") or "(pusty wynik)"
        agent = data.get("selectedAgent") or "?"
        model = data.get("selectedModel") or "bez modelu"
        elapsed = time.perf_counter() - start

        print(
            f"Jarvis [{agent} / {model} / {elapsed:.1f}s]: {result}"
        )

        if data.get("errors"):
            print(f"(uwagi: {'; '.join(data['errors'][:2])})")

        # Do historii zapisujemy tylko pomyślnie zakończone zadania.
        if status == 200 and data.get("status") == "COMPLETED":
            history.append({
                "role": "user",
                "content": shorten(text),
            })
            history.append({
                "role": "assistant",
                "content": shorten(result),
            })

            # Zachowujemy jedynie ostatnie wiadomości.
            history = history[-MAX_HISTORY_MESSAGES:]


if __name__ == "__main__":
    main()
