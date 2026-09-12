# InvalidSessionFix

Automatischer Session-Fix fuer **Minecraft 1.8.9 Forge / LabyMod 3**, wenn ein Microsoft-Login waehrend einer laufenden Instanz wegen eines abgelaufenen Minecraft-Access-Tokens mit **Invalid session** scheitert.

## Wichtig: MultiMC Account-Ablauf in v1.0.0 behoben

Die alte v1.0.0 hat den Microsoft-Refresh-Token direkt aus MultiMC `accounts.json` gelesen, benutzt und anschliessend die Datei aktualisiert. Das ist fuer einen laufenden MultiMC-Prozess unsicher: MultiMC haelt seinen Account parallel im Speicher. Dadurch konnte MultiMC spaeter noch mit einem alten/rotierten Token arbeiten und den Account als **abgelaufen** markieren.

**Ab v1.1.0 gilt deshalb:**

- `accounts.json` wird von InvalidSessionFix **weder gelesen noch veraendert**
- MultiMC-Refresh-Tokens werden **nicht mehr konsumiert oder rotiert**
- InvalidSessionFix bekommt nach einmaligem `/sessionfix login` einen **eigenen Microsoft Device-Flow Refresh-Token**
- nur dieser eigene Token wird unter `.minecraft/config/invalidsessionfix-auth.json` gespeichert und erneuert
- die laufende Minecraft-Session kann danach weiterhin automatisch repariert werden

Falls MultiMC durch die alte Version bereits "Das Konto ist abgelaufen und muss manuell angemeldet werden" anzeigt, muss der Account **einmal direkt in MultiMC neu angemeldet werden**. Eine Forge-Mod kann diesen Launcher-Dialog nicht abfangen, weil Minecraft zu diesem Zeitpunkt noch gar nicht gestartet wurde.

## Was die Mod macht

- prueft den laufenden Minecraft-Access-Token regelmaessig gegen die Minecraft-Services
- reagiert zusaetzlich beim Oeffnen des Disconnect-Screens
- erneuert bei Bedarf Microsoft -> Xbox -> XSTS -> Minecraft Access Token
- ersetzt `Minecraft.session` direkt zur Laufzeit, ohne Minecraft neu zu starten
- reconnectet nach einer erfolgreichen Reparatur optional automatisch zum letzten Server
- schreibt niemals Passwoerter oder Token in Chat oder Log
- greift nicht mehr auf den Account-Speicher des Launchers zu

## Zielplattform

- Minecraft **1.8.9**
- Forge **11.15.1.2318**
- Java **8**
- MultiMC / kompatible Launcher mit Microsoft-Account
- LabyMod 3 kann innerhalb der Forge-1.8.9-Instanz parallel genutzt werden

## Ersteinrichtung nach Update von v1.0.0

1. Alte `InvalidSessionFix-1.0.0.jar` ersetzen.
2. Falls MultiMC den Account schon als abgelaufen meldet: Account dort einmal neu anmelden.
3. Minecraft starten.
4. Im Spiel einmal ausfuehren:

```text
/sessionfix login
```

Der Browser wird mit der Microsoft-Anmeldung geoeffnet. Im Chat steht zusaetzlich der Device-Code. Nach erfolgreicher Anmeldung wird der private InvalidSessionFix-Token gespeichert.

## Bedienung

```text
/sessionfix status
/sessionfix login
/sessionfix logout
/sessionfix check
/sessionfix repair
/sessionfix auto on|off
/sessionfix reconnect on|off
```

- `/sessionfix login` richtet die isolierte Microsoft-Verknuepfung ein oder erneuert sie.
- `/sessionfix logout` entfernt nur den InvalidSessionFix-Token. MultiMC bleibt unangetastet.
- `/sessionfix repair` erzwingt einen kompletten Token-Refresh ueber den InvalidSessionFix-eigenen Token.

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

Der private Auth-Token liegt separat unter:

```text
.minecraft/config/invalidsessionfix-auth.json
```

Diese Datei ist sensibel und darf nicht weitergegeben werden.

## Ablauf ab v1.1.0

```text
Einmalig /sessionfix login
   |
   +-- Microsoft Device Flow
   +-- eigener Refresh-Token -> invalidsessionfix-auth.json
   +-- MultiMC accounts.json bleibt unangetastet

Minecraft laeuft
   |
   +-- Hintergrundpruefung alle 5 Minuten
   |       |
   |       +-- Token gueltig -> nichts tun
   |       |
   |       +-- Token ungueltig
   |              |
   |              +-- eigenen InvalidSessionFix Refresh-Token erneuern
   |              +-- Xbox User Token holen
   |              +-- XSTS fuer Minecraft holen
   |              +-- Minecraft Access Token holen
   |              +-- Profil gegen gestarteten Account pruefen
   |              +-- nur eigenen Refresh-Token speichern
   |              +-- laufende Minecraft Session ersetzen
   |
   +-- Disconnect-Screen
           |
           +-- sofortige Session-Pruefung
           +-- bei erfolgreichem Fix optional Auto-Reconnect
```

## Sicherheit

InvalidSessionFix v1.1.0:

- benutzt einen separaten OAuth Device-Flow
- uebertraegt Tokens ausschliesslich an die offiziellen Microsoft-, Xbox- und Minecraft-Authentifizierungsendpunkte
- gibt Tokens weder im Chat noch im Log aus
- verweigert das Einsetzen einer erneuerten Session, wenn Minecraft-Profil und aktuell gestarteter Account nicht zusammenpassen
- liest oder schreibt **keine MultiMC `accounts.json` mehr**

## Release

Aktuelle stabile Version: **v1.1.0**

Die Release-JAR wird reproduzierbar durch GitHub Actions mit Java 8 und ForgeGradle gebaut.

## Build

```bash
gradle clean build
```

Die fertige Datei liegt anschliessend unter:

```text
build/libs/InvalidSessionFix-1.1.0.jar
```

## Technischer Hinweis

Der Microsoft Device-Flow verwendet dieselbe oeffentliche OAuth-Client-ID, die MultiMC fuer seine Microsoft-Anmeldung verwendet. InvalidSessionFix besitzt dabei aber einen **eigenen Login-Vorgang und einen eigenen Refresh-Token**. Dadurch findet keine Token-Rotation mehr auf dem von MultiMC verwalteten Token statt.

## Lizenz

MIT
