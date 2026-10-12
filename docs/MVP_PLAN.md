# Plan wykonania JARVIS MVP

## Cel wdrożenia

Powstaje jedna lokalna aplikacja JVM zgodna z dokumentami źródłowymi: przetwarza polecenie w zadanie, dobiera agenta i model bez zależności od konkretnego sprzętu, używa bezpiecznych narzędzi, waliduje wynik oraz zapamiętuje rezultat w SQLite.

## Zakres tego wydania

1. Szkielet Maven i publiczne API HTTP.
2. Core: zadania, orkiestracja, kontekst, walidacja i ograniczone odzyskiwanie po błędach.
3. Rejestr czterech agentów i router oparty o możliwości: General, System, Coding, Research.
4. Wykrywanie bieżących zasobów CPU, RAM, systemu i dysków.
5. Rejestr oraz router modeli, który filtruje po możliwościach, dostępnej pamięci, kontekście, prywatności i tool-calling.
6. Abstrakcja `ModelProvider` z implementacją klienta Ollama. Brak Ollamy nie blokuje narzędzi systemowych ani API.
7. Bezpieczne File, System i Terminal Tool oraz centralna polityka ryzyka i potwierdzeń.
8. Pamięć długoterminowa SQLite i audyt działań.
9. Testy jednostkowe routerów, bezpieczeństwa i ścieżki integracyjnej System Agent.

## Kolejność i kryteria akceptacji

| Etap | Rezultat | Kryterium |
| --- | --- | --- |
| Core | `Task` przechodzi przez stany do wyniku | W każdym wyniku widoczne są agent, model oraz status |
| Routing | Wybierany jest agent i możliwy model | Model wymagający niedostępnej pamięci nie może przejść filtra |
| Tools i bezpieczeństwo | Narzędzia są opisane ryzykiem | Operacja `DANGEROUS` nie wykonuje się bez zgody |
| Pamięć | Wynik zadania jest zapisywany lokalnie | Wpis można odczytać przez API |
| API i testy | Serwer udostępnia funkcje MVP | `mvn test` kończy się sukcesem |

## Poza zakresem MVP

Voice, vision, GUI, pluginy, autonomiczne wykonywanie, chmurowe modele i baza wektorowa pozostają punktami rozszerzeń. Interfejsy w tym wydaniu nie wiążą Core ani z Ollamą, ani z konkretną kartą GPU.
