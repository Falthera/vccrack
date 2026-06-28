# VoiceChatCrack

Client-side proof-of-concept mod for educational security research targeting Simple Voice Chat group passwords.

## Features

- Configurable brute-force via `/vccrack`
- Customizable character set and password length range
- Wordlist directory at `config/vccrack/wordlists/`
- Stop/status commands
- Progress reporting

## Build

```bash
./gradlew.bat clean build
```

## Usage

```
/vccrack <group_name> [min_len] [max_len]
/vccrack stop
/vccrack status
/vccrack charset <str>
/vccrack wordlist load <file>
```

## Disclaimer

For educational and security research purposes only. Use on servers you own or have explicit permission to test.
