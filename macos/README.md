# PTY-Limit dauerhaft anheben (macOS / Linux)

Jede `claude`-Session (und jeder `claude-auto`-Neustart) braucht ein **Pseudo-TTY**.
Bei vielen parallelen Sessions + Switch-Neustarts kann das **macOS-Default-Limit**
`kern.tty.ptmx_max = 511` erschöpfen → `claude` scheitert mit
`forkpty: Device not configured` (Linux: openpty-Fehler).

Der `claude-auto`-Wrapper fängt das seit dem PTY-Pre-flight sauber ab (klare Meldung +
Warten auf freie PTYs statt kryptischem Abbruch). Damit es gar nicht erst eng wird,
hebt dieser **LaunchDaemon** das Limit beim Boot auf 2048.

## macOS — installieren
```bash
sudo cp macos/com.4dataclub.ptmx-max.plist /Library/LaunchDaemons/
sudo chown root:wheel /Library/LaunchDaemons/com.4dataclub.ptmx-max.plist
sudo launchctl load -w /Library/LaunchDaemons/com.4dataclub.ptmx-max.plist
```
Sofort (ohne Reboot) zusätzlich:
```bash
sudo sysctl -w kern.tty.ptmx_max=2048
```
Prüfen: `sysctl kern.tty.ptmx_max` → sollte `2048` zeigen.

## macOS — entfernen
```bash
sudo launchctl unload -w /Library/LaunchDaemons/com.4dataclub.ptmx-max.plist
sudo rm /Library/LaunchDaemons/com.4dataclub.ptmx-max.plist
```

## Linux — Äquivalent
Statt LaunchDaemon in `/etc/sysctl.d/99-pty.conf`:
```
kernel.pty.max = 4096
```
dann `sudo sysctl --system`.

> Bewusst **nicht** automatisch von `setup.sh` installiert — das Anheben eines
> System-Kernel-Limits soll eine explizite, sichtbare Nutzer-Entscheidung sein.
