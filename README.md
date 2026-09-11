# InvalidSessionFix

Automatischer Session-Fix fuer **Minecraft 1.8.9 Forge / LabyMod 3**, wenn ein Microsoft-Login in einer MultiMC-Instanz wegen eines abgelaufenen Minecraft-Access-Tokens mit **Invalid session** scheitert.

## Was die Mod macht

- prueft den laufenden Minecraft-Access-Token regelmaessig gegen die Minecraft-Services
- reagiert zusaetzlich beim Oeffnen des Disconnect-Screens
- liest den zum gestarteten Account passenden Microsoft-Refresh-Token lokal aus MultiMC `accounts.json`
- erneuert Microsoft -> Xbox -> XSTS -> Minecraft Access Token
- ersetzt `Minecraft.session` direkt zur Laufzeit, ohne Minecraft neu zu starten
- schreibt den erneuerten Token atomar nach `accounts.json` zurueck
- legt davor `accounts.json.invalidsessionfix.bak` als Backup an
- reconnectet nach einer erfolgreichen Reparatur optional automatisch zum letzten Server
- schreibt niemals Passwoerter oder Token in das Log

## Zielplattform

- Minecraft **1.8.9**
- Forge **11.15.1.2318**
- Java **8**
- MultiMC mit Microsoft-Account
- LabyMod 3 kann innerhalb der Forge-1.8.9-Instanz parallel genutzt werden

Die erste Version ist bewusst auf MultiMC ausgelegt. Andere Launcher koennen ihre Microsoft-Tokens anders speichern oder verschluesseln.

## Bedienung

Normalerweise ist keine Bedienung noetig. Auto-Fix und Auto-Reconnect sind standardmaessig aktiv.

```text
/sessionfix status
/sessionfix check
/sessionfix repair
/sessionfix auto on|off
/sessionfix reconnect on|off
```

`/sessionfix repair` erzwingt einen kompletten Token-Refresh, auch wenn der aktuelle Token noch als gueltig erkannt wird.

## Konfiguration

Nach dem ersten Start:

```text
.minecraft/config/invalidsessionfix.cfg
```

Standardwerte:

```text
autoRepair=true
autoReconnect=true
checkIntervalSeconds=300
initialDelaySeconds=10
```

## Ablauf

```text
Minecraft laeuft
   |
   +-- Hintergrundpruefung alle 5 Minuten
   |       |
   |       +-- Token gueltig -> nichts tun
   |       |
   |       +-- Token ungueltig
   |              |
   |              +-- MultiMC accounts.json finden
   |              +-- Microsoft Refresh Token erneuern
   |              +-- Xbox User Token holen
   |              +-- XSTS fuer Minecraft holen
   |              +-- Minecraft Access Token holen
   |              +-- Profil gegen gestarteten Account pruefen
   |              +-- accounts.json sichern + aktualisieren
   |              +-- laufende Minecraft Session ersetzen
   |
   +-- Disconnect-Screen
           |
           +-- sofortige Session-Pruefung
           +-- bei erfolgreichem Fix optional Auto-Reconnect
```

## Sicherheit

`accounts.json` enthaelt hochsensible Login-Tokens. InvalidSessionFix:

- liest nur die lokale Datei
- uebertraegt Tokens ausschliesslich an die offiziellen Microsoft-, Xbox- und Minecraft-Authentifizierungsendpunkte
- gibt Tokens weder im Chat noch im Log aus
- verweigert das Einsetzen einer erneuerten Session, wenn Minecraft-Profil und aktuell gestarteter Account nicht zusammenpassen
- erstellt vor einer Aenderung ein lokales Backup der MultiMC-Accountdatei

## Release

Aktuelle stabile Version: **v1.0.0**

Die Release-JAR wird reproduzierbar durch GitHub Actions mit Java 8 und ForgeGradle gebaut.

## Build

```bash
gradle clean build
```

Die fertige Datei liegt anschliessend unter:

```text
build/libs/InvalidSessionFix-1.0.0.jar
```

## Technischer Hinweis

Der Microsoft-Refresh-Token in MultiMC ist an MultiMC's oeffentliche OAuth-Client-ID gebunden. Deshalb verwendet der MultiMC-Provider dieselbe oeffentliche Client-ID fuer den Refresh. Die Mod implementiert keinen Passwort-Login und speichert keine Microsoft-Zugangsdaten.

## Lizenz

MIT
