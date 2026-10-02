// CF-Alarm – Website: externe Links in einem neuen Tab öffnen.
document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('a[href^="http"]').forEach(link => {
        if (link.hostname === location.hostname) return;
        link.setAttribute('rel', 'external noopener');
        link.setAttribute('target', '_blank');
    });
});
