# PencilNotes: privacy policy

*Last updated: 2026-11-15. [Italiano più sotto](#pencilnotes-informativa-sulla-privacy).*

PencilNotes is a note-taking application for Android, iOS and computers (Windows, macOS, Linux),
made by one developer and published as open source
(<https://github.com/valerioisufi/DrawView-KMP>). It has no accounts, no advertising, no analytics
and no crash reporting. The developer runs no server that receives your notes.

## What stays on your device

Your notes, folders, imported PDFs and images, the recordings and transcripts of your lectures, the
search index and the models you download are kept in the private storage of the application on
your device. They are not part of the backups of the system: to keep a copy, use Export → Archive,
or the sync with your other devices. Uninstalling the application deletes them.

The API keys you enter and the identity of your device (the key that signs what it sends to your
other devices) are kept encrypted by the keystore of the system (Android Keystore, iOS Keychain).

## What leaves your device, and only when you ask for it

* **The lecture assistant with Gemini.** If you enter your own Gemini API key and choose the online
  models, PencilNotes sends to the Gemini API of Google (`generativelanguage.googleapis.com`) what the
  feature needs: the audio or the transcript of the lecture, the text of your slides and notes, and
  images of the pages you are looking at. The same holds for "Ask your notes" when you choose Gemini.
  Google processes them under your key and under its own terms
  (<https://ai.google.dev/gemini-api/terms>). Without a key, nothing is sent.
* **Ollama on your network.** If you choose a model of Ollama, the same data goes to the computer of
  your local network that runs it, which you choose. "Find on the network" asks the devices found on
  your local network whether they run Ollama.
* **Downloading models.** When you download a speech or language model, it is downloaded from
  Hugging Face (`huggingface.co`). Only the request for the file is sent.
* **Working with your other devices (sync).** On the local network, your devices find each other
  (mDNS) and connect directly. Over the internet (off by default), they connect directly when they
  can, otherwise through a relay: the public relays of n0 or one you set. What travels is end-to-end
  encrypted with the key of each shared document: a relay sees the addresses of the devices, when
  they talk and how much, never the content. The devices you invite to a document receive that
  document, and the lectures recorded in it, audio included.

## Permissions

* **Microphone**: to record the lectures you start. The recording goes on with the screen off, with
  a notification, until you stop it.
* **Notifications**: to show that a lecture is being recorded or a sync is running.
* **Network and local network**: for the features above.

## Children

PencilNotes is not directed to children under 13.

## Your rights and contact

The developer holds no data of yours, so there is nothing to access or delete on their side; you
delete your data by deleting your notes or the application. For any question:
`<contact address>`.

Changes to this policy are published at this address, with the date above.

---

# PencilNotes: informativa sulla privacy

*Ultimo aggiornamento: 15 novembre 2026.*

PencilNotes è un'applicazione per prendere appunti su Android, iOS e computer (Windows, macOS,
Linux), fatta da un solo sviluppatore e pubblicata come open source
(<https://github.com/valerioisufi/DrawView-KMP>). Non ha account, pubblicità, statistiche d'uso né
segnalazione dei crash. Lo sviluppatore non gestisce alcun server che riceva le tue note.

## Cosa resta sul tuo dispositivo

Le note, le cartelle, i PDF e le immagini importati, le registrazioni e le trascrizioni delle
lezioni, l'indice di ricerca e i modelli che scarichi restano nella memoria privata
dell'applicazione sul tuo dispositivo. Non fanno parte dei backup del sistema: per averne una copia
usa Esporta → Archivio, oppure la sincronizzazione con gli altri tuoi dispositivi. Disinstallare
l'applicazione li cancella.

Le chiavi API che inserisci e l'identità del dispositivo (la chiave che firma ciò che invia agli
altri tuoi dispositivi) sono cifrate dal portachiavi del sistema (Android Keystore, Portachiavi di
iOS).

## Cosa esce dal dispositivo, e solo quando lo chiedi

* **L'assistente alle lezioni con Gemini.** Se inserisci una tua chiave API di Gemini e scegli i
  modelli online, PencilNotes invia all'API Gemini di Google (`generativelanguage.googleapis.com`) ciò
  che serve alla funzione: l'audio o la trascrizione della lezione, il testo delle slide e delle
  note, le immagini delle pagine che stai guardando. Lo stesso vale per "Chiedi alle tue note" quando
  scegli Gemini. Google li tratta con la tua chiave e secondo i suoi termini
  (<https://ai.google.dev/gemini-api/terms>). Senza chiave non viene inviato nulla.
* **Ollama nella tua rete.** Se scegli un modello di Ollama, gli stessi dati vanno al computer della
  tua rete locale che lo esegue, scelto da te. "Cerca nella rete" chiede ai dispositivi trovati nella
  tua rete locale se eseguono Ollama.
* **Il download dei modelli.** Quando scarichi un modello di riconoscimento vocale o di linguaggio,
  viene scaricato da Hugging Face (`huggingface.co`). Viene inviata solo la richiesta del file.
* **Il lavoro con gli altri tuoi dispositivi (sincronizzazione).** Nella rete locale i dispositivi si
  trovano (mDNS) e si collegano direttamente. Via internet (disattivata di default) si collegano
  direttamente quando possono, altrimenti attraverso un relay: quelli pubblici di n0 o uno che
  imposti tu. Ciò che viaggia è cifrato end-to-end con la chiave di ogni documento condiviso: un relay
  vede gli indirizzi dei dispositivi, quando comunicano e quanto, mai il contenuto. I dispositivi che
  inviti in un documento ricevono quel documento e le lezioni registrate al suo interno, audio
  compreso.

## Permessi

* **Microfono**: per registrare le lezioni che avvii. La registrazione continua a schermo spento, con
  una notifica, finché non la fermi.
* **Notifiche**: per mostrare che una lezione è in registrazione o che una sincronizzazione è in
  corso.
* **Rete e rete locale**: per le funzioni descritte sopra.

## Minori

PencilNotes non è rivolta a bambini sotto i 13 anni.

## I tuoi diritti e i contatti

Lo sviluppatore non conserva alcun tuo dato, quindi non c'è nulla da consultare o cancellare da parte
sua; cancelli i tuoi dati cancellando le note o l'applicazione. Per qualsiasi domanda:
`<indirizzo di contatto>`.

Le modifiche a questa informativa sono pubblicate a questo indirizzo, con la data qui sopra.
