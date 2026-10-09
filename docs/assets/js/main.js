// CF-Alarm – Website.
document.addEventListener('DOMContentLoaded', () => {
    // Externe Links in einem neuen Tab öffnen.
    document.querySelectorAll('a[href^="http"]').forEach(link => {
        if (link.hostname === location.hostname) return;
        link.setAttribute('rel', 'external noopener');
        link.setAttribute('target', '_blank');
    });

    // Schalter Nacht/Tag. Ohne JavaScript bleibt er versteckt, dann gilt die Systemeinstellung.
    const wurzel = document.documentElement;
    const schalter = document.querySelector('.ansicht-schalter');
    const themeFarbe = document.querySelector('meta[name="theme-color"]');
    if (!schalter) return;

    const zeige = ansicht => {
        const nacht = ansicht === 'nacht';
        wurzel.setAttribute('data-theme', ansicht);
        schalter.querySelector('.ansicht-name').textContent = nacht ? 'Nacht' : 'Tag';
        schalter.setAttribute('aria-label', nacht
            ? 'Ansicht: Nacht. Zur Tagansicht wechseln'
            : 'Ansicht: Tag. Zur Nachtansicht wechseln');
        schalter.querySelector('.symbol-mond').hidden = !nacht;
        schalter.querySelector('.symbol-sonne').hidden = nacht;
        if (themeFarbe) themeFarbe.setAttribute('content', nacht ? '#0b1426' : '#bfd8f0');
    };

    zeige(wurzel.getAttribute('data-theme') === 'nacht' ? 'nacht' : 'tag');
    schalter.hidden = false;
    schalter.addEventListener('click', () => {
        const neu = wurzel.getAttribute('data-theme') === 'nacht' ? 'tag' : 'nacht';
        try {
            sessionStorage.setItem('cfa-ansicht', neu);
        } catch (e) {
            // Nicht speicherbar: Umschalten wirkt trotzdem bis zum Seitenwechsel.
        }
        zeige(neu);
    });
});
