# Testo di consegna in italiano

Questo file contiene il messaggio pronto da inoltrare al docente. La documentazione tecnica del
progetto è in inglese; questo è l'unico documento in italiano ed esiste solo per la comunicazione di
consegna.

---

## Messaggio da inoltrare

Buongiorno,

le invio la consegna del progetto **ProofChain 1.0.0**, backend per la catena di custodia di prove
digitali sviluppato per l'unità formativa Java.

**Repository:** `pianic2/its-java-proofchain`
**Branch:** `main`

> Il merge è stato eseguito: `main` contiene l'intero lavoro degli Sprint 3–6 e la build è verde.
> Il branch `ijpc-8-sprint-6-final-delivery` porta in più tre commit di sola documentazione, fra cui
> questo stesso messaggio; il codice valutabile è interamente su `main`.

### Da dove iniziare

Il documento di ingresso è **`docs/release/1.0.0/Professor-Delivery.md`**: contiene requisiti, avvio
rapido, comandi di verifica, punti di accesso a Swagger e Postman, evidenze di verifica e un
percorso di valutazione consigliato.

Il documento principale per la valutazione tecnica è **`docs/Technical-Report.md`**, accompagnato da
`docs/Architecture.md` (otto diagrammi) e dai record di decisione architetturale `docs/adr/`.

### Avvio rapido

```bash
git clone <url-repository> && cd its-java-proofchain
cp .env.example .env      # impostare le password e un PROOFCHAIN_JWT_SECRET Base64 di almeno 32 byte
docker compose build && docker compose up -d
```

Swagger UI su `http://localhost:8080/swagger-ui.html`.

Esecuzione della suite di test (richiede Docker attivo, i test di integrazione avviano un PostgreSQL
reale tramite Testcontainers):

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

### Cosa è stato verificato

La certificazione è stata eseguita da un **clone pulito separato**, quindi il risultato non dipende
dalla macchina di sviluppo:

- `clean verify` eseguito **due volte consecutive** senza modifiche fra le due esecuzioni: entrambe
  con esito positivo, working tree identica prima della prima e dopo la seconda;
- **443 test unitari** (0 fallimenti, 4 skip dichiarati) e **377 test di integrazione** (0
  fallimenti);
- copertura JaCoCo: **91,66% di righe**, 77,81% di rami, contro una soglia configurata dello 0,51;
- collection Postman eseguita **due volte** con reset distruttivo intermedio: 98 richieste e 200
  assertion identiche in entrambe le esecuzioni.

Le evidenze complete sono in `docs/release/1.0.0/Certification-Report.md`.

### Punti che le segnalo esplicitamente

Preferisco indicarli io piuttosto che lasciarli scoprire durante la valutazione.

1. **PostgreSQL è l'unico database supportato.** È una deviazione deliberata rispetto alla traccia:
   l'applicazione non è stata riprogettata per la portabilità fra database. Le migrazioni Flyway
   sono specifiche per PostgreSQL e alcune garanzie — il trigger append-only sugli eventi, il
   trigger sulle transizioni di ciclo di vita e la strategia di locking pessimistico — dipendono
   dalla sua semantica. La motivazione è documentata in `docs/ITS-Compliance.md` e negli ADR.

2. **L'analisi delle vulnerabilità delle dipendenze non è mai stata eseguita.** Il profilo OWASP
   Dependency-Check è configurato ed è stato lanciato, ma l'ambiente di build non raggiunge i
   database NVD e CISA, quindi si interrompe senza dati. **Non è stata svolta alcuna analisi e non
   va dedotta alcuna assenza di vulnerabilità.** Il comando da eseguire in una rete con accesso è
   indicato nella documentazione.

3. **Il progetto è stato sviluppato con assistenza AI**, con verifica automatizzata a ogni passo.
   `docs/release/1.0.0/AI-Validation-Record.md` documenta il metodo e i difetti che quella verifica
   ha effettivamente intercettato. **Non è stata eseguita alcuna validazione umana** e la sua
   approvazione non è ancora avvenuta.

4. Restano alcuni limiti funzionali minori — un file di zero byte trattato come errore tecnico
   anziché come verifica non valida, un metodo HTTP non supportato che restituisce 500 invece di
   405 — tutti elencati con la relativa spiegazione in `docs/release/1.0.0/Known-Limitations.md`.

### Demo

`docs/Demo-Guide.md` contiene una procedura numerata di circa 12–15 minuti, con precondizioni,
richieste esatte ed esiti attesi. Include gli scenari di manomissione che mostrano la catena di
custodia mentre rileva una corruzione dei dati: si eseguono solo in ambiente usa-e-getta e
richiedono un passo manuale esplicito.

Resto a disposizione per qualsiasi chiarimento.

Cordiali saluti,
Niccolò Piazzi

---

## Nota per il Project Owner

Prima di inviare il messaggio, verificare che questi punti siano ancora accurati:

- il merge su `main` è già avvenuto; se nel frattempo sono stati creati il tag `uf14-final-2026` e
  la GitHub Release, citarli qui;
- i tre commit di sola documentazione rimasti sul branch di consegna vanno portati su `main` prima
  di taggare, altrimenti il tag non includerà questo stesso messaggio;
- se OWASP Dependency-Check è stato eseguito in una rete con accesso a NVD, sostituire il punto 2
  con l'esito reale;
- il commit citato è quello certificato: se vengono aggiunti altri commit, aggiornarlo.
