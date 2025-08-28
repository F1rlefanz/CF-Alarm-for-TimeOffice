// CF Alarm for Time Office - Modern JavaScript with Hide-on-Scroll Navigation
// Progressive Web App registration
if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
        // Future: register service worker for offline capabilities
        console.log('PWA capabilities ready');
    });
}

// Modern loading performance
document.addEventListener('DOMContentLoaded', () => {
    // Fade in animation trigger
    document.body.classList.add('loaded');
    
    // Modern interaction enhancements
    const links = document.querySelectorAll('a[href^="http"]');
    links.forEach(link => {
        link.setAttribute('rel', 'external noopener');
        if (!link.hasAttribute('target')) {
            link.setAttribute('target', '_blank');
        }
    });

    // HIDE-ON-SCROLL Navigation
    initHideOnScrollNav();
});

// Enhanced accessibility
document.addEventListener('keydown', (e) => {
    // Skip link functionality
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

// HIDE-ON-SCROLL NAVIGATION Implementation
function initHideOnScrollNav() {
    const nav = document.getElementById('main-nav') || document.querySelector('.nav');
    if (!nav) return;

    // Add ID if not present
    if (!nav.id) {
        nav.id = 'main-nav';
    }

    let lastScrollY = window.scrollY;
    let isNavVisible = true;
    const navHeight = nav.offsetHeight;
    const scrollThreshold = navHeight; // Start hiding after nav height

    function updateNavVisibility() {
        const currentScrollY = window.scrollY;
        const scrollDirection = currentScrollY > lastScrollY ? 'down' : 'up';
        const scrollDistance = Math.abs(currentScrollY - lastScrollY);

        // Don't hide if we're at the very top
        if (currentScrollY <= scrollThreshold) {
            if (!isNavVisible) {
                showNav();
            }
        } 
        // Hide on scroll down (after threshold)
        else if (scrollDirection === 'down' && scrollDistance > 5 && isNavVisible) {
            hideNav();
        } 
        // Show on scroll up
        else if (scrollDirection === 'up' && scrollDistance > 5 && !isNavVisible) {
            showNav();
        }

        lastScrollY = currentScrollY;
    }

    function hideNav() {
        nav.classList.add('nav-hidden');
        isNavVisible = false;
    }

    function showNav() {
        nav.classList.remove('nav-hidden');
        isNavVisible = true;
    }

    // Throttled scroll event for better performance
    let ticking = false;
    function onScroll() {
        if (!ticking) {
            requestAnimationFrame(() => {
                updateNavVisibility();
                ticking = false;
            });
            ticking = true;
        }
    }

    window.addEventListener('scroll', onScroll, { passive: true });
}