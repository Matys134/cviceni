# Rovně a silně

Tréninkový plán s animovanými cviky: stabilizace pánve, krční páteř, silový trénink a volitelný okruh.
Záznamy se ukládají jen v telefonu, nikam se neposílají.

## Instalace do telefonu (Android 8 a novější)

1. V telefonu otevři [poslední verzi](https://github.com/Matys134/cviceni/releases/latest) a stáhni soubor `rovne-a-silne-….apk`.
2. Otevři stažený soubor. Android se zeptá, jestli může prohlížeč instalovat aplikace. Povol to („Povolit z tohoto zdroje“) a vrať se zpět.
3. Klepni na **Instalovat**. Aplikace „Rovně a silně“ se objeví mezi ostatními.

## Aktualizace

Aplikace se po spuštění sama podívá, jestli na GitHubu není novější verze (nejvýš jednou za 6 hodin).
Když je, nabídne **Stáhnout a nainstalovat**. Napoprvé Android jednou požádá o povolení instalovat
aktualizace z této aplikace. Ručně se dá zkontrolovat dole v aplikaci přes **Zkontrolovat aktualizace**.
Záznamy při aktualizaci zůstávají.

## Přenos záznamů

V záložce **Přehled → Záloha záznamů** dej na jednom zařízení **Exportovat**, zkopírovaný text vlož na druhém
přes **Importovat**. Záznamy se spojí, nic se nesmaže. Funguje mezi aplikací, souborem v počítači i verzí na claude.ai.

## Struktura

| Cesta | Co to je |
|---|---|
| `Rovne-a-silne/` | Samotná aplikace (HTML), popis cviků a návod. Totéž se balí do APK. |
| `android/` | Android obal: WebView, klíč obrazovky při časovači, sdílení zálohy, aktualizace z GitHubu. |
| `.github/workflows/release.yml` | Sestaví podepsané APK a vydá ho jako GitHub Release. |

## Vydání nové verze

```bash
git tag -a v1.1.0 -m "Co je nového (zobrazí se v aplikaci při aktualizaci)"
git push origin v1.1.0
```

GitHub Actions sestaví APK, podepíše ho a zveřejní v Releases. Číslo verze musí být vždy vyšší než minulé.
APK se podepisuje klíčem uloženým v tajných proměnných repozitáře (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`).
Bez stejného klíče by se nová verze přes starou nenainstalovala.
