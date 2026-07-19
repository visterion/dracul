import type { ExplainerTable } from './explainers'

const de: ExplainerTable = {
  'orders.bracket': {
    title: 'Geschützter Auftrag',
    sections: [
      {
        anchor: 'bracket',
        heading: 'Was ist ein geschützter Auftrag?',
        body: 'Ein Kauf mit automatischer Absicherung — drei zusammengehörige Orders: Einstieg (der Kauf), Ziel (Gewinnmitnahme) und Stop (Verlustschutz). Ziel und Stop werden erst scharf, wenn der Einstieg ausgeführt ist.',
      },
    ],
  },
  'hunter.spin': {
    title: 'strigoi-spin — Abspaltungen',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Spaltet ein Konzern eine Tochter als eigene Aktie ab, werfen viele Fonds die neue Aktie automatisch ab (sie passt nicht in ihr Mandat). Dieser Zwangsverkauf drückt den Kurs unter den fairen Wert.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'SEC-Registrierungen (Form 10-12B) der letzten 60 Tage plus das Term-Sheet der Abspaltung (Verhältnis, Stichtag, Größe).' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Verfolgt jede Abspaltung durch ihre Phasen und meldet einen Kandidaten im Zwangsverkaufs-Fenster nach der Abspaltung.' },
    ],
  },
  'hunter.insider': {
    title: 'strigoi-insider — Insider-Käufe im Cluster',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Kaufen mehrere Führungskräfte kurz hintereinander eigene Aktien, wissen sie oft etwas Gutes, das der Markt noch nicht sieht.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'SEC Form-4-Meldungen, gefiltert auf Cluster (≥3 Käufer in 30 Tagen, zusammen über 500.000 $); angereichert mit Firmengröße, Analystenabdeckung und ob die Käufe routinemäßig oder opportunistisch sind.' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Bevorzugt kleine, unbeachtete Firmen mit echten (nicht geplanten) Käufen und meldet die überzeugendsten Cluster.' },
    ],
  },
  'hunter.echo': {
    title: 'strigoi-echo — Drift nach Quartalszahlen',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Nach überraschend guten Zahlen läuft der Kurs oft noch Wochen weiter — der Markt reagiert zu langsam.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'Zahlen-Kalender, Gewinn-Überraschung (Zulassung ab 5 % EPS-Überraschung; der SUE-Wert verfeinert danach die Überzeugung), Marktreaktion am Berichtstag und Analysten-Revisionen.' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Nur echte, cash-gedeckte Überraschungen (keine Bilanztricks), kein baldiger nächster Bericht, und der Markt hat am Berichtstag positiv reagiert.' },
    ],
  },
  'hunter.lazarus': {
    title: 'strigoi-lazarus — Qualität am Jahrestief',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Solide Firmen, die nur wegen Marktpanik nahe ihrem Jahrestief notieren, erholen sich oft — Qualität zum Ausverkaufspreis.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'Deine Watchlist nahe dem 52-Wochen-Tief (rund 10 %); Piotroski-F-Score (Bilanzqualität), Altman-Z (Pleiterisiko), Bewertung und Timing-Signale.' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Verlangt hohe Bilanzqualität, schließt Pleitekandidaten und „fallende Messer" aus und meldet nur günstig-und-gesund.' },
    ],
  },
  'hunter.index': {
    title: 'strigoi-index — Index-Aufnahme',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Wird eine Aktie neu in einen großen Index aufgenommen, müssen Index-Fonds sie kaufen — der erzwungene Kauf treibt den Kurs bis zum Stichtag.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'Angekündigte Index-Änderungen (S&P-Pressemitteilungen, Russell) mit Ankündigungs- und Stichtag sowie Liquidität.' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Meldet nur, solange das Kauf-Fenster (heute bis Stichtag) offen ist; bereits wirksame Aufnahmen sind zu spät.' },
    ],
  },
  'hunter.merger': {
    title: 'strigoi-merger — Übernahme-Arbitrage',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Nach Ankündigung einer Übernahme handelt die Zielaktie knapp unter dem Angebotspreis — die Lücke ist der Gewinn, wenn der Deal durchgeht.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'SEC-Deal-Filings (DEFM14A, Tender Offers) der letzten 45 Tage, Angebotspreis und Bedingungen aus dem Term-Sheet, Kurs und erwarteter Abschluss.' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Wägt den annualisierten Spread gegen das Absturzrisiko bei Deal-Bruch, dämpft Aktien-Deals und koppelt den Horizont an den erwarteten Abschluss.' },
    ],
  },
  'hunter.overview': {
    title: 'Die Jäger (Meute)',
    sections: [
      { anchor: 'was', heading: 'Was sind die Jäger?', body: 'Sechs spezialisierte Jäger („Strigoi") suchen nachts je genau eine dokumentierte Marktanomalie und legen Funde in die Datenbank. Jeder Jäger hat eine eigene Idee, eigene Datenquellen und einen eigenen Auslöser. Über das (i) an jedem Jäger siehst du seine Idee, Inputs und wie er zuschlägt.' },
      { anchor: 'spin', heading: 'spin — Abspaltungen', body: 'Zwangsverkauf nach der Abspaltung einer Tochter drückt den Kurs unter den fairen Wert.' },
      { anchor: 'insider', heading: 'insider — Insider-Käufe', body: 'Mehrere Führungskräfte kaufen kurz hintereinander eigene Aktien — ein Cluster ist Überzeugung.' },
      { anchor: 'echo', heading: 'echo — Drift nach Zahlen', body: 'Nach überraschend guten Quartalszahlen läuft der Kurs oft noch Wochen weiter.' },
      { anchor: 'lazarus', heading: 'lazarus — Qualität am Tief', body: 'Gesunde Firmen nahe ihrem Jahrestief erholen sich oft — Qualität zum Ausverkaufspreis.' },
      { anchor: 'index', heading: 'index — Index-Aufnahme', body: 'Neu-Aufnahme in einen großen Index zwingt Index-Fonds zum Kauf bis zum Stichtag.' },
      { anchor: 'merger', heading: 'merger — Übernahme-Arbitrage', body: 'Die Zielaktie handelt unter dem Angebotspreis — die Lücke ist der Gewinn, wenn der Deal durchgeht.' },
    ],
  },
}

export default de
