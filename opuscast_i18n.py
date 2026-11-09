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
    "Looking for your TV…": "Hľadám televízor…",
    "Drag to jump (with “Show on PC” on you see where you're going)":
        "Potiahnutím preskočíte (so zapnutým „Zobraziť na PC“ vidíte, kam)",
    "Skip intro": "Preskočiť úvod",
    "Start the video now": "Spustiť video hneď",
    "Show a muted copy of the video here, in step with the TV (P)":
        "Zobrazí tu stlmenú kópiu videa, súbežne s televízorom (P)",
    "Play the Skynet boot animation before each video": "Pred každým videom prehrá animáciu spúšťania Skynetu",
    "Scan again": "Hľadať znova",
    "_Play": "_Prehrať",
    "_Cancel": "_Zrušiť",
    "Videos": "Videá",
    "All files": "Všetky súbory",
    "Preparing subtitles…": "Pripravujem titulky…",
    "Skynet is booting…": "Skynet sa spúšťa…",
    "Preparing the intro…": "Pripravujem úvod…",
    "Starting on the TV…": "Spúšťam v televízore…",
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
    "The TV didn't accept that: {error}": "Televízor to neprijal: {error}",
    "Search again": "Hľadať znova",
    "Try again": "Skúsiť znova",
    "Waiting for the TV…": "Čakám na televízor…",
    "The TV hasn't taken the video yet ({error}). If the TV asks whether to allow this PC, "
    "choose Allow - the video then starts by itself.":
        "Televízor video zatiaľ neprevzal ({error}). Ak sa televízor pýta, či povoliť tento počítač, "
        "zvoľte Povoliť – video sa potom spustí samo.",
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
    "TV address (default: the last TV used, or the first one found)":
        "adresa televízora (predvolene naposledy použitý alebo prvý nájdený)",
    "start at TIME, e.g. 1:02:30": "začať v čase TIME, napr. 1:02:30",
    "subtitles: auto, a subtitle file, or a number from --subs":
        "titulky: auto, súbor s titulkami alebo číslo z --subs",
    "list the subtitles found for the video": "vypísať titulky nájdené pre video",
    "play the Skynet boot intro first": "najprv prehrať úvod Skynet",
    "show devices on the network and which can play video": "ukázať zariadenia v sieti a ktoré vedia prehrávať video",
    "stop playback on the TV and exit": "zastaviť prehrávanie v televízore a skončiť",
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
    # music, photos, the desktop (and other TVs)
    "Philips TV: turn on Digital Media Renderer - DMR (All Settings > Wireless and Networks > Wired or Wi-Fi), "
    "then scan again":
        "Televízor Philips: zapnite Digital Media Renderer – DMR (Všetky nastavenia > Bezdrôtové a siete > "
        "Káblové alebo Wi‑Fi) a hľadajte znova",
    "Cast the desktop": "Prenos pracovnej plochy",
    "Music the TV doesn't play, converted to MP3": "Hudba, ktorú televízor neprehrá, prevedená do MP3",
    "Plays music only": "Prehrá len hudbu",
    "Smart TV with its media player (DLNA) off - look for DLNA, media sharing or DMR in its network settings":
        "Smart TV s vypnutým prehrávačom médií (DLNA) – v jeho nastaveniach siete hľadajte DLNA, zdieľanie médií "
        "alebo DMR",
    "Google Cast device (Chromecast, Google TV, Shield) - it doesn't take DLNA, which OpusCast uses":
        "Zariadenie Google Cast (Chromecast, Google TV, Shield) – nepodporuje DLNA, ktoré OpusCast používa",
    "couldn't convert the song ({error})": "skladbu sa nepodarilo previesť ({error})",
    "cancelled": "zrušené",
    "H.264 (graphics card)": "H.264 (grafická karta)",
    "GStreamer's PipeWire plugin is missing": "chýba zásuvný modul PipeWire pre GStreamer",
    "GStreamer couldn't read the shared screen": "GStreamer nedokázal čítať zdieľanú obrazovku",
    "with sound": "so zvukom",
    "no sound": "bez zvuku",
    "there's no desktop to cast here": "nie je tu pracovná plocha, ktorú by sa dalo preniesť",
    "desktop casting needs ffmpeg": "prenos plochy potrebuje ffmpeg",
    "desktop casting on Wayland needs GStreamer's PipeWire plugin":
        "prenos plochy vo Waylande potrebuje zásuvný modul PipeWire pre GStreamer",
    "screen sharing was stopped": "zdieľanie obrazovky sa zastavilo",
    "ffmpeg here can't encode video": "ffmpeg v tomto počítači nevie kódovať video",
    "the screen capture didn't start": "snímanie obrazovky sa nespustilo",
    "the screen capture stopped": "snímanie obrazovky sa zastavilo",
    "screen sharing was cancelled": "zdieľanie obrazovky bolo zrušené",
    "the desktop refused to start screen sharing": "pracovné prostredie odmietlo spustiť zdieľanie obrazovky",
    "the desktop didn't share a screen": "pracovné prostredie nezdieľalo žiadnu obrazovku",
    "this desktop doesn't offer screen sharing to apps (no screen-cast portal: {error})":
        "toto pracovné prostredie neponúka aplikáciám zdieľanie obrazovky (chýba portál na zdieľanie obrazovky: "
        "{error})",
    "the shared screen went away": "zdieľaná obrazovka zmizla",
    "can't reach the desktop's screen sharing ({error})": "zdieľanie obrazovky nie je dostupné ({error})",
    "Choose videos, music or photos (Ctrl+O)": "Vybrať videá, hudbu alebo fotky (Ctrl+O)",
    "Videos, music or photos - pick several songs or photos to play them one after another":
        "Videá, hudba alebo fotky – vyberte viac skladieb či fotiek a prehrajú sa jedna po druhej",
    "Show this PC's screen and sound on the TV (Ctrl+D)": "Ukázať obrazovku a zvuk tohto počítača v televízore (Ctrl+D)",
    "Slideshow on / off (Space)": "Prezentácia zap. / vyp. (medzerník)",
    "Choose what to play on the TV": "Vyberte, čo prehrať v televízore",
    "Videos, music and photos": "Videá, hudba a fotky",
    "Music": "Hudba",
    "Photos": "Fotky",
    "The TV hasn't taken it yet ({error}). If the TV asks whether to allow this PC, choose Allow - "
    "it then starts by itself.":
        "Televízor to zatiaľ neprevzal ({error}). Ak sa televízor pýta, či povoliť tento počítač, zvoľte Povoliť "
        "– potom sa to spustí samo.",
    "The TV hasn't started loading it. A firewall on this PC may be blocking it. This lets the TV in:":
        "Televízor to nezačal načítavať. Možno ho blokuje firewall v tomto počítači. Toto televízor pustí:",
    "The one with this window": "Tá s týmto oknom",
    "Your desktop": "Vaša pracovná plocha",
    "Drop videos, music or photos here": "Pretiahnite sem videá, hudbu alebo fotky",
    "They play on your TV - control them with the TV remote or the buttons here.":
        "Prehrajú sa v televízore – ovládajte ich diaľkovým ovládačom alebo tlačidlami tu.",
    "Choose…": "Vybrať…",
    "Cast Desktop": "Preniesť plochu",
    "Desktop casting settings": "Nastavenia prenosu plochy",
    "Previous song (Page Up)": "Predchádzajúca skladba (Page Up)",
    "Next song (Page Down)": "Ďalšia skladba (Page Down)",
    "Previous": "Predchádzajúca",
    "Previous photo (←)": "Predchádzajúca fotka (←)",
    "Next": "Ďalšia",
    "Next photo (→)": "Ďalšia fotka (→)",
    "How long each photo stays": "Ako dlho zostane každá fotka",
    "The TV shows the screen a second or two late.": "Televízor ukazuje obrazovku o sekundu-dve neskôr.",
    "All songs": "Všetky skladby",
    "Songs": "Skladby",
    "That doesn't look like a video, song or photo on this PC.":
        "Toto nevyzerá ako video, skladba ani fotka v tomto počítači.",
    "Waiting for the TV - it starts as soon as the TV is found.":
        "Čakám na televízor – spustí sa, hneď ako sa televízor nájde.",
    "Firewall updated - starting again.": "Firewall upravený – spúšťam znova.",
    "On the TV": "V televízore",
    "Sending the photo…": "Posielam fotku…",
    "Preparing the photo…": "Pripravujem fotku…",
    "Screen": "Obrazovka",
    "Picture": "Obraz",
    "Smoothness": "Plynulosť",
    "Sound from this PC": "Zvuk z tohto počítača",
    "The TV shows the screen a second or two late - fine for slides, photos and videos, not for games.":
        "Televízor ukazuje obrazovku o sekundu-dve neskôr – na prezentácie, fotky a videá to stačí, na hry nie.",
    "Asked when you start": "Opýta sa pri spustení",
    "All screens": "Všetky obrazovky",
    "Casting the desktop needs a little more software. Install it with:":
        "Prenos plochy potrebuje ešte trochu softvéru. Nainštalujte ho takto:",
    "Choose what to show in the window that opens…": "V okne, ktoré sa otvorí, vyberte, čo ukázať…",
    "Starting the screen capture…": "Spúšťam snímanie obrazovky…",
    "Desktop": "Pracovná plocha",
    "The PC's sound isn't going to the TV: there's no sound server to record it from.":
        "Zvuk počítača nejde do televízora: nie je tu zvukový server, z ktorého by sa dal nahrávať.",
    "Open": "Otvoriť",
    "LIVE": "NAŽIVO",
    "Converting the song for the TV…": "Prevádzam skladbu pre televízor…",
    "Can't play {name}: {error}": "{name} sa nedá prehrať: {error}",
    "Can't read that photo: {error}": "Fotka sa nedá načítať: {error}",
    "{n} s each": "každá {n} s",
    "{device} plays music only - choose a TV for videos, photos and the desktop.":
        "{device} prehrá len hudbu – na videá, fotky a plochu vyberte televízor.",
    "Couldn't start it on the TV: {error}": "V televízore sa to nepodarilo spustiť: {error}",
    "Couldn't cast the desktop: {error}": "Plochu sa nepodarilo preniesť: {error}",
    "Desktop casting stopped: {error}": "Prenos plochy sa zastavil: {error}",
    "The TV didn't take this photo: {error}": "Televízor túto fotku neprevzal: {error}",
    "The TV didn't start the song. It may not play this format.":
        "Televízor skladbu nespustil. Možno tento formát neprehrá.",
    "The TV didn't start showing the desktop.": "Televízor nezačal ukazovať plochu.",
    "1080p - sharpest": "1080p – najostrejšie",
    "720p - lighter on the PC and Wi-Fi": "720p – menej zaťaží počítač aj Wi‑Fi",
    "30 pictures a second": "30 snímok za sekundu",
    "60 pictures a second - needs a fast PC": "60 snímok za sekundu – potrebuje rýchly počítač",
    "Use this speaker": "Použiť tento reproduktor",
    "Screen {n}": "Obrazovka {n}",
    # Windows and macOS
    "macOS hasn't allowed OpusCast to record the screen yet. Allow it in System Settings → Privacy & Security → "
    "Screen Recording, then start OpusCast again.":
        "macOS zatiaľ nepovolil OpusCastu nahrávať obrazovku. Povoľte ho v Systémových nastaveniach → Súkromie "
        "a bezpečnosť → Nahrávanie obrazovky a spustite OpusCast znova.",
    "macOS hasn't allowed OpusCast to record the screen yet. Allow it in System Preferences → Security & Privacy → "
    "Privacy → Screen Recording, then start OpusCast again.":
        "macOS zatiaľ nepovolil OpusCastu nahrávať obrazovku. Povoľte ho v Systémových nastaveniach → Bezpečnosť "
        "a súkromie → Súkromie → Nahrávanie obrazovky a spustite OpusCast znova.",
    "ffmpeg here can't see the screen (no AVFoundation screen capture)":
        "ffmpeg v tomto počítači nevidí obrazovku (chýba snímanie obrazovky AVFoundation)",
    "Qt Multimedia isn't installed": "Qt Multimedia nie je nainštalované",
    "TV volume (↑/↓)": "Hlasitosť televízora (↑/↓)",
    "macOS doesn't hand the Mac's sound to apps by itself. With BlackHole (free) installed and set as the output, "
    "the sound goes to the TV too.":
        "macOS sám od seba nedáva aplikáciám zvuk Macu. S nainštalovaným BlackHole (zadarmo) nastaveným ako "
        "výstup ide do televízora aj zvuk.",
    "The TV hasn't started loading it. macOS's firewall may be blocking it: in System Settings → Network → "
    "Firewall → Options, let OpusCast accept incoming connections.":
        "Televízor to nezačal načítavať. Možno ho blokuje firewall macOS: v Systémových nastaveniach → Sieť → "
        "Firewall → Možnosti povoľte OpusCastu prichádzajúce spojenia.",
    "The TV hasn't started loading it. macOS's firewall may be blocking it: in System Preferences → Security & "
    "Privacy → Firewall → Firewall Options, let OpusCast accept incoming connections.":
        "Televízor to nezačal načítavať. Možno ho blokuje firewall macOS: v Systémových nastaveniach → Bezpečnosť "
        "a súkromie → Firewall → Možnosti firewallu povoľte OpusCastu prichádzajúce spojenia.",
    "The Mac's sound isn't going to the TV: macOS doesn't hand it to apps by itself. Install BlackHole (free) and "
    "set it as the output to send it along.":
        "Zvuk Macu nejde do televízora: macOS ho aplikáciám sám nedáva. Nainštalujte BlackHole (zadarmo) "
        "a nastavte ho ako výstup, potom pôjde aj zvuk.",
    "“Show on PC” isn't available here ({reason}).": "„Zobraziť na PC“ tu nie je k dispozícii ({reason}).",
    "The TV may not show {name}: this PC can't convert this kind of photo. This helps:":
        "Televízor možno {name} neukáže: tento počítač tento druh fotiek nevie previesť. Pomôže toto:",
    "(keep this open - Ctrl+C stops)": "(nechajte toto otvorené – Ctrl+C zastaví)",
    "Play videos, music and photos from this PC on your TV, or show it the desktop.":
        "Prehrá videá, hudbu a fotky z tohto počítača v televízore alebo mu ukáže pracovnú plochu.",
    "videos, songs, photos or a folder": "videá, skladby, fotky alebo priečinok",
    "slideshow: seconds per photo": "prezentácia: sekundy na fotku",
    "show this PC's screen and sound on the TV": "ukázať obrazovku a zvuk tohto počítača v televízore",
    "desktop: picture size": "plocha: veľkosť obrazu",
    "desktop: pictures a second": "plocha: snímky za sekundu",
    "desktop on X11: which screen (default: the main one)": "plocha v X11: ktorá obrazovka (predvolene hlavná)",
    "desktop: without the PC's sound": "plocha: bez zvuku počítača",
    "give a video, songs or photos (or --desktop / --list / --stop)":
        "zadajte video, skladby alebo fotky (alebo --desktop / --list / --stop)",
    "That doesn't look like a video, song or photo.": "Toto nevyzerá ako video, skladba ani fotka.",
    "Stopped on the TV.": "Zastavené v televízore.",
    "(Ctrl+C takes it off the TV)": "(Ctrl+C ju z televízora zloží)",
    "--subs is for videos": "--subs je pre videá",
    "Showing the desktop on {tv}: {how}": "Plocha sa ukazuje na {tv}: {how}",
    "Converting {name} to MP3 - the TV doesn't play this format…":
        "Prevádzam {name} do MP3 – televízor tento formát neprehrá…",
    "There's no screen {n} - this PC has {count}.": "Obrazovka {n} neexistuje – tento počítač ich má {count}.",
    # subtitle languages
    "Slovak": "Slovenčina", "Czech": "Čeština", "English": "Angličtina", "German": "Nemčina",
    "Hungarian": "Maďarčina", "Polish": "Poľština", "French": "Francúzština", "Spanish": "Španielčina",
    "Italian": "Taliančina", "Russian": "Ruština", "Ukrainian": "Ukrajinčina", "Portuguese": "Portugalčina",
    "Dutch": "Holandčina", "Croatian": "Chorvátčina", "Serbian": "Srbčina", "Slovenian": "Slovinčina",
    "Romanian": "Rumunčina", "Japanese": "Japončina", "Chinese": "Čínština", "Korean": "Kórejčina",
    # sharing the desktop: when the TV leaves it
    "OpusCast on {pc}": "OpusCast na {pc}",
    "The TV": "Televízor",
    "Show on the TV again": "Znova ukázať v televízore",
    "Stop sharing": "Prestať zdieľať",
    "Not on the TV": "Nie je v televízore",
    "Not on the TV now.": "Teraz nie je v televízore.",
    "Showing it on the TV again…": "Znova sa ukazuje v televízore…",
    "Still shared, but not on {tv} now": "Stále sa zdieľa, ale na {tv} teraz nie je",
    "On the TV: choose “{source}” in its sources to go back.":
        "V televízore sa vrátite výberom „{source}“ medzi jeho zdrojmi.",
    "{tv} left the desktop - it's still shared.": "Na {tv} už plocha nie je – stále sa však zdieľa.",
    "{tv} left the desktop - it's still shared. To go back from the TV, choose “{source}” in its sources or media "
    "player.":
        "Na {tv} už plocha nie je – stále sa však zdieľa. V televízore sa vrátite výberom „{source}“ medzi jeho "
        "zdrojmi alebo v prehrávači médií.",
    "The TV didn't show it again - is it on?": "Televízor ju znova neukázal – je zapnutý?",
    "Nobody watched the desktop for 30 minutes, so OpusCast stopped sharing it.":
        "Plochu 30 minút nikto nesledoval, preto sa jej zdieľanie skončilo.",
    "Bring it back if the TV leaves": "Vrátiť plochu, keď ju televízor opustí",
    "If the TV leaves the desktop (Back, another source…), OpusCast shows it there again after a few seconds. "
    "Leave it again right away to keep it off.":
        "Keď televízor plochu opustí (tlačidlo Späť, iný zdroj…), OpusCast ju tam o pár sekúnd znova ukáže. "
        "Ak ju hneď znova opustíte, už sa nevráti.",
    "Left the desktop on the TV? Choose “{source}” in the TV's sources to go back.":
        "Ak v televízore odídete z plochy, vrátite sa výberom „{source}“ medzi zdrojmi televízora.",
    "Left it on the TV by mistake? Choose “{source}” in the TV's sources to go back, or press Enter here.":
        "Ak v televízore omylom odídete z plochy, vráťte sa výberom „{source}“ medzi zdrojmi televízora alebo "
        "tu stlačte Enter.",
    "The TV left the desktop - it's still shared. To go back, choose “{source}” in the TV's sources, or press "
    "Enter here (Ctrl+C stops).":
        "Televízor opustil plochu – stále sa zdieľa. Vrátite sa výberom „{source}“ medzi zdrojmi televízora "
        "alebo stlačením Enter tu (Ctrl+C zastaví).",
    "The TV left the desktop - it's still shared. Press Enter to show it there again (Ctrl+C stops).":
        "Televízor opustil plochu – stále sa zdieľa. Stlačením Enter ju tam znova ukážete (Ctrl+C zastaví).",
    "Back on the TV.": "Plocha je znova v televízore.",
    "The TV left the desktop - showing it there again…": "Televízor opustil plochu – znova sa tam ukazuje…",
    "The TV didn't show it again - is it on? Press Enter to try again.":
        "Televízor ju znova neukázal – je zapnutý? Stlačením Enter to skúsite znova.",
    "desktop: if the TV leaves it, show it there again by itself (leave it twice in a row to stop)":
        "plocha: keď ju televízor opustí, sama sa tam znova ukáže (ak ju opustíte dvakrát za sebou, už sa nevráti)",
}

LANGUAGES = {"sk": SK}
LANG = _system_language()
_table = LANGUAGES.get(LANG, {})


def _(text):
    return _table.get(text, text) if text else text
