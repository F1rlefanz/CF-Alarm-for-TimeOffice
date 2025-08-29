// CF Alarm for Time Office - Einfaches JavaScript ohne Hide-on-Scroll
if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
        console.log('PWA capabilities ready');
    });
}

document.addEventListener('DOMContentLoaded', () => {
    // Fade in animation
    document.body.classList.add('loaded');
    
    // External links
    const links = document.querySelectorAll('a[href^="http"]');
    links.forEach(link => {
        link.setAttribute('rel', 'external noopener');
        if (!link.hasAttribute('target')) {
            link.setAttribute('target', '_blank');
        }
    });
    
    console.log('Simple navigation loaded - no hide-on-scroll complexity!');
});

// Accessibility
document.addEventListener('keydown', (e) => {
    if (e.key === 'Tab' && !e.shiftKey) {
        const skipLink = document.querySelector('.sr-only');
        if (document.activeElement === skipLink) {
            e.preventDefault();
            const mainContent = document.getElementById('main-content');
            if (mainContent) {
                mainContent.focus();
            }
        }
    }
});