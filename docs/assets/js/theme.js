// CF-Alarm – Website: Ansicht „nacht“ oder „tag“ wählen, BEVOR die Seite gezeichnet wird.
// Läuft synchron im <head>, damit nichts aufblitzt. Nachts (19–6 Uhr) Nacht, sonst Tag.
// Hat jemand im Kopf umgeschaltet, gilt das für diesen Besuch (sessionStorage) –
// beim nächsten Besuch entscheidet wieder die Uhrzeit. Ein Link mit ?ansicht=nacht
// oder ?ansicht=tag legt die Ansicht fest (zum Teilen und für die Bildschirmprüfung).
(function () {
    var ansicht = null;
    var aufLink = /[?&]ansicht=(nacht|tag)(?:&|$)/.exec(location.search);
    try {
        if (aufLink) sessionStorage.setItem('cfa-ansicht', aufLink[1]);
        ansicht = sessionStorage.getItem('cfa-ansicht');
    } catch (e) {
        // Speicher gesperrt (privates Fenster o. Ä.): dann Link oder Uhrzeit.
        if (aufLink) ansicht = aufLink[1];
    }
    if (ansicht !== 'nacht' && ansicht !== 'tag') {
        var stunde = new Date().getHours();
        ansicht = (stunde >= 19 || stunde < 6) ? 'nacht' : 'tag';
    }
    document.documentElement.setAttribute('data-theme', ansicht);
})();
