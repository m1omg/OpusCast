"""OpusCast translations. Slovak is picked automatically from the system language;
OPUSCAST_LANG=en / sk overrides it. Add a language by adding a dictionary to LANGUAGES."""
import os


def _system_language():
    forced = os.environ.get("OPUSCAST_LANG")
    if forced:
        return forced.lower()[:2]
    for var in ("LANGUAGE", "LC_ALL", "LC_MESSAGES", "LANG"):
        value = os.environ.get(var)
        if value and value.split(":")[0] not in ("C", "POSIX"):
            return value.split(":")[0].lower()[:2]
    return "en"


SK = {
    # core / network scan
    "Command line (opuscast)": "Príkazový riadok (opuscast)",
    "App window": "Okno aplikácie",
    "Built-in subtitle tracks": "Titulky vložené vo videách",
    "Skynet intro": "Úvod Skynet",
    "Unknown device": "Neznáme zariadenie",
    "converting these subtitles needs ffmpeg": "na prevod týchto titulkov treba ffmpeg",
    "Install: {what}": "Nainštalujte: {what}",
    "Show on PC": "Zobraziť na PC",
    "this device can't play media": "toto zariadenie nevie prehrávať médiá",
    "built-in": "vložené",
    "This computer": "Tento počítač",
    "Private Wi-Fi address": "Súkromná Wi‑Fi adresa",
    "forced": "vynútené",
    "couldn't read these subtitles ({error})": "tieto titulky sa nedali načítať ({error})",
    "Recommended for a smooth 4K preview": "Odporúčané pre plynulý 4K náhľad",
    "the TV doesn't support {what}": "televízor nepodporuje {what}",
    "picture-based - can't be sent to the TV": "obrázkové – nedajú sa poslať do televízora",
    "can't open a port for the video server ({error})": "nedá sa otvoriť port pre server videa ({error})",
    "needs": "potrebuje",
    "the TV refused {action}: {detail}": "televízor odmietol {action}: {detail}",
    "can't reach the TV ({error})": "televízor neodpovedá ({error})",
    "Audio only - can't show video": "Len zvuk – video nezobrazí",
    "Can play video": "Môže prehrávať video",
    "Router - can't play video": "Router – video neprehrá",
    "No casting support found - if this is a TV, turn it on and scan again":
        "Nepodporuje prehrávanie zo siete – ak je to televízor, zapnite ho a hľadajte znova",
    "needs ffmpeg to convert": "na prevod treba ffmpeg",
    "Track {n}": "Stopa {n}",
    "can't read the subtitle file ({error})": "súbor s titulkami sa nedá prečítať ({error})",
    "Router": "Router",
    "Private Wi-Fi address (probably a phone or laptop) - can't play video":
        "Súkromná Wi‑Fi adresa (asi telefón alebo notebook) – video neprehrá",
    "No casting support found": "Nepodporuje prehrávanie zo siete",
    "Media server / NAS - can't play video": "Mediálny server / NAS – video neprehrá",
    "Network device - can't play video": "Sieťové zariadenie – video neprehrá",
    # app window
    "GStreamer isn't installed": "GStreamer nie je nainštalovaný",
    "the GStreamer base plugins aren't installed": "nie sú nainštalované základné pluginy GStreamer",
    "the GStreamer GTK plugin isn't installed": "nie je nainštalovaný GTK plugin pre GStreamer",
    "Choose a video (Ctrl+O)": "Vybrať video (Ctrl+O)",
    "Looking for your TV…": "Hľadám televízor…",
    "Drag to jump (with “Show on PC” on you see where you're going)":
        "Potiahnutím preskočíte (so zapnutým „Zobraziť na PC“ vidíte, kam)",
    "Skip intro": "Preskočiť úvod",
    "Start the video now": "Spustiť video hneď",
    "Show a muted copy of the video here, in step with the TV (P)":
        "Zobrazí tu stlmenú kópiu videa, súbežne s televízorom (P)",
    "Play the Skynet boot animation before each video": "Pred každým videom prehrá animáciu spúšťania Skynetu",
    "Scan again": "Hľadať znova",
    "Choose a video to play on the TV": "Vyberte video na prehratie v televízore",
    "_Play": "_Prehrať",
    "_Cancel": "_Zrušiť",
    "Videos": "Videá",
    "All files": "Všetky súbory",
    "That doesn't look like a video file on this PC.": "Toto nevyzerá ako video súbor v tomto počítači.",
    "Preparing subtitles…": "Pripravujem titulky…",
    "Skynet is booting…": "Skynet sa spúšťa…",
    "Preparing the intro…": "Pripravujem úvod…",
    "Starting on the TV…": "Spúšťam v televízore…",
    "The TV hasn't started loading the video. A firewall on this PC may be blocking it. This lets the TV in:":
        "Televízor nezačal načítavať video. Možno ho blokuje firewall v tomto počítači. Toto televízor pustí:",
    "Skynet is coming online": "Skynet sa pripája",
    "Loading subtitles…": "Načítavam titulky…",
    "Subtitles: loading…": "Titulky: načítavam…",
    "Choose a subtitle file": "Vyberte súbor s titulkami",
    "_Use": "_Použiť",
    "Subtitles": "Titulky",
    "OpusCast needs PyGObject and GTK 3:": "OpusCast potrebuje PyGObject a GTK 3:",
    "Choose the TV - see which devices on your network can play video":
        "Vybrať televízor – ukáže, ktoré zariadenia v sieti vedia prehrávať video",
    "TV": "TV",
    "Drop a video here": "Pretiahnite sem video",
    "It plays on your TV - control it with the TV remote or the buttons here.":
        "Prehrá sa v televízore – ovládajte ho diaľkovým ovládačom alebo tlačidlami tu.",
    "Choose Video…": "Vybrať video…",
    "Recent": "Nedávne",
    "Videos you play will show up here, so you can carry on where you left off.":
        "Tu sa zobrazia prehrané videá, aby ste mohli pokračovať tam, kde ste prestali.",
    "30s": "30 s",
    "10s": "10 s",
    "Back 30 seconds (Shift+←)": "Späť o 30 sekúnd (Shift+←)",
    "Back 10 seconds (←)": "Späť o 10 sekúnd (←)",
    "Play / pause (Space)": "Prehrať / pozastaviť (medzerník)",
    "Forward 10 seconds (→)": "Dopredu o 10 sekúnd (→)",
    "Forward 30 seconds (Shift+→)": "Dopredu o 30 sekúnd (Shift+→)",
    "Stop": "Zastaviť",
    "Stop playback": "Zastaviť prehrávanie",
    "Subtitles (S)": "Titulky (S)",
    "Keep OpusCast open while you watch": "Počas pozerania nechajte OpusCast otvorený",
    "Pick a device marked “Can play video”. Routers, phones and speakers can't show videos.":
        "Vyberte zariadenie označené „Môže prehrávať video“. Routery, telefóny a reproduktory video nezobrazia.",
    "Scanning your network…": "Prehľadávam sieť…",
    "Open this list to scan your network.": "Otvorením tohto zoznamu sa prehľadá sieť.",
    "Watched": "Pozreté",
    "Remove from this list": "Odstrániť zo zoznamu",
    "Waiting for the TV - the video starts as soon as it's found.":
        "Čakám na televízor – video sa spustí, hneď ako sa nájde.",
    "Firewall updated - starting the video again.": "Firewall upravený – spúšťam video znova.",
    "The TV didn't start the video. It may not support this file's format "
    "(Dolby Vision files often don't work on Samsung TVs).":
        "Televízor video nespustil. Možno nepodporuje formát tohto súboru "
        "(súbory s Dolby Vision v televízoroch Samsung často nefungujú).",
    "Subtitles: set on the TV": "Titulky: nastavené na TV",
    "Choose a subtitle file…": "Vybrať súbor s titulkami…",
    "Subtitles were changed with the TV remote. The TV doesn't tell the PC what's on, "
    "so pick an option here to set them from the PC.":
        "Titulky sa zmenili diaľkovým ovládačom. Televízor počítaču neoznámi, čo je zapnuté – "
        "vyberte možnosť tu a nastavíte ich z PC.",
    "This TV can't switch subtitles on command, so OpusCast restarts the video with the subtitles you pick.":
        "Tento televízor nevie prepínať titulky na povel, preto OpusCast spustí video znova s vybranými titulkami.",
    "Subtitles not showing? The TV's own subtitle setting may be off. Turn subtitles on "
    "once with the TV remote - the PC can't change that setting.":
        "Titulky sa nezobrazujú? Možno je vypnuté nastavenie titulkov v televízore. Raz ich zapnite "
        "diaľkovým ovládačom – z PC sa toto nastavenie zmeniť nedá.",
    "Open video": "Otvoriť video",
    "No TV found": "Televízor sa nenašiel",
    "No TV": "Bez TV",
    "Couldn't find a TV. Make sure it's switched on and on the same network as this PC.":
        "Televízor sa nenašiel. Skontrolujte, či je zapnutý a v rovnakej sieti ako tento počítač.",
    "Connected to {tv}": "Pripojené k {tv}",
    "Play from the beginning": "Prehrať od začiatku",
    "Allow it (asks for your password)": "Povoliť (vypýta heslo)",
    "Firewall updated.": "Firewall upravený.",
    "The firewall wasn't changed.": "Firewall sa nezmenil.",
    "Playing": "Prehráva sa",
    "Paused": "Pozastavené",
    "Loading…": "Načítava sa…",
    "The PC preview can't play this video ({error}). The TV isn't affected.":
        "Náhľad na PC toto video neprehrá ({error}). Televízora sa to netýka.",
    "Devices on your network": "Zariadenia v sieti",
    "Not started": "Nezačaté",
    "File not found: {path}": "Súbor sa nenašiel: {path}",
    "Skipping the Skynet intro: it {reason}.": "Úvod Skynet preskakujem – {reason}.",
    "The Skynet intro failed ({error}) - playing the video straight away.":
        "Úvod Skynet zlyhal ({error}) – video sa spustí hneď.",
    "Carrying on from {pos}.": "Pokračuje sa od {pos}.",
    "Start from the beginning": "Začať od začiatku",
    "Playing on {tv}": "Prehráva sa na {tv}",
    "Looking for subtitles…": "Hľadám titulky…",
    "Off": "Vypnuté",
    "No subtitles found next to this video or inside it.": "Pri tomto videu ani v ňom sa nenašli titulky.",
    "Subtitles built into videos need ffmpeg:": "Titulky vložené vo videách potrebujú ffmpeg:",
    "Couldn't use those subtitles: {error}": "Tieto titulky sa nedajú použiť: {error}",
    "“Show on PC” needs more GStreamer parts ({reason}). Install them with:":
        "„Zobraziť na PC“ potrebuje ďalšie časti GStreamer ({reason}). Nainštalujte ich:",
    "Couldn't start the video on the TV: {error}": "Video sa v televízore nepodarilo spustiť: {error}",
    "The TV didn't accept that: {error}": "Televízor to neprijal: {error}",
    "Search again": "Hľadať znova",
    "Show devices": "Zobraziť zariadenia",
    "In use": "Používa sa",
    "Use this TV": "Použiť tento televízor",
    "Resume at {pos} of {dur}": "Pokračovať od {pos} z {dur}",
    "Can't read that file: {error}": "Súbor sa nedá prečítať: {error}",
    "Can't reach {tv} - is it on?": "{tv} neodpovedá – je zapnutý?",
    "Subtitles: {name}": "Titulky: {name}",
    "Subtitles: none": "Titulky: žiadne",
    "Subtitles: off": "Titulky: vypnuté",
    "The Skynet intro {reason}. Install with:": "Úvod Skynet {reason}. Nainštalujte:",
    "Resume at {pos}": "Pokračovať od {pos}",
    "{app} can't start": "{app} sa nedá spustiť",
    # command line
    "playing": "prehráva",
    "paused": "pozastavené",
    "stopped": "zastavené",
    "loading": "načítava",
    "idle": "nečinný",
    "not responding": "neodpovedá",
    "Scanning the network...": "Prehľadávam sieť...",
    "Play a video from this PC on your TV.": "Prehrá video z tohto počítača v televízore.",
    "video file to play": "video súbor na prehratie",
    "TV address (default: the last TV used, or the first one found)":
        "adresa televízora (predvolene naposledy použitý alebo prvý nájdený)",
    "start at TIME, e.g. 1:02:30": "začať v čase TIME, napr. 1:02:30",
    "subtitles: auto, a subtitle file, or a number from --subs":
        "titulky: auto, súbor s titulkami alebo číslo z --subs",
    "list the subtitles found for the video": "vypísať titulky nájdené pre video",
    "play the Skynet boot intro first": "najprv prehrať úvod Skynet",
    "show devices on the network and which can play video": "ukázať zariadenia v sieti a ktoré vedia prehrávať video",
    "stop playback on the TV and exit": "zastaviť prehrávanie v televízore a skončiť",
    "give a video file (or --list / --stop)": "zadajte video súbor (alebo --list / --stop)",
    "No TV found. Is it switched on and on the same network? (opuscast --list shows what's there)":
        "Televízor sa nenašiel. Je zapnutý a v rovnakej sieti? (opuscast --list ukáže, čo v sieti je)",
    "Stopped.": "Zastavené.",
    "(keep this open while you watch - Ctrl+C stops)": "(počas pozerania nechajte toto otvorené – Ctrl+C zastaví)",
    "Finished.": "Hotovo.",
    "TV:": "TV:",
    "No usable subtitles found - playing without.": "Nenašli sa použiteľné titulky – prehrávam bez nich.",
    "No such subtitle file: {path}": "Taký súbor s titulkami neexistuje: {path}",
    "The TV hasn't loaded the video yet - a firewall on this PC may be blocking it.":
        "Televízor ešte nenačítal video – možno ho blokuje firewall v tomto počítači.",
    "Can't read the time {time!r} - use e.g. 1:02:30, 45:00 or 300":
        "Čas {time!r} sa nedá prečítať – použite napr. 1:02:30, 45:00 alebo 300",
    "There's no subtitle number {n} - see opuscast VIDEO --subs":
        "Titulky číslo {n} neexistujú – pozrite opuscast VIDEO --subs",
    "No such file: {path}": "Taký súbor neexistuje: {path}",
    "Playing on {tv}: {title}": "Prehráva sa na {tv}: {title}",
    "Error: {error}": "Chyba: {error}",
    # Skynet intro
    "needs ffmpeg": "potrebuje ffmpeg",
    "needs pycairo (python3-cairo on Debian/Mint, python-cairo on Arch/CachyOS)":
        "potrebuje pycairo (python3-cairo na Debian/Mint, python-cairo na Arch/CachyOS)",
    "Skynet intro {reason}": "Úvod Skynet {reason}",
    # subtitle languages
    "Slovak": "Slovenčina", "Czech": "Čeština", "English": "Angličtina", "German": "Nemčina",
    "Hungarian": "Maďarčina", "Polish": "Poľština", "French": "Francúzština", "Spanish": "Španielčina",
    "Italian": "Taliančina", "Russian": "Ruština", "Ukrainian": "Ukrajinčina", "Portuguese": "Portugalčina",
    "Dutch": "Holandčina", "Croatian": "Chorvátčina", "Serbian": "Srbčina", "Slovenian": "Slovinčina",
    "Romanian": "Rumunčina", "Japanese": "Japončina", "Chinese": "Čínština", "Korean": "Kórejčina",
}

LANGUAGES = {"sk": SK}
LANG = _system_language()
_table = LANGUAGES.get(LANG, {})


def _(text):
    return _table.get(text, text) if text else text
