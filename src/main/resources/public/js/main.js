/**
 * Global UX Improvements for ISHM
 */

document.addEventListener('DOMContentLoaded', () => {
    initProgressBar();
    initActiveLinks();
    initBackToTop();
    initPageTransitions();
    initBreadcrumbs();
});

// Progress Bar Simulation
function initProgressBar() {
    const nprogress = document.createElement('div');
    nprogress.id = 'nprogress';
    nprogress.innerHTML = '<div class="bar"><div class="peg"></div></div>';
    document.body.appendChild(nprogress);
    
    const bar = nprogress.querySelector('.bar');
    bar.style.width = '0%';
    bar.style.display = 'none';

    window.startProgress = () => {
        bar.style.display = 'block';
        let width = 0;
        const interval = setInterval(() => {
            if (width >= 90) {
                clearInterval(interval);
            } else {
                width += Math.random() * 5;
                bar.style.width = width + '%';
            }
        }, 100);
    };

    window.stopProgress = () => {
        bar.style.width = '100%';
        setTimeout(() => {
            bar.style.display = 'none';
            bar.style.width = '0%';
        }, 200);
    };

    // Intercept link clicks
    document.querySelectorAll('a').forEach(link => {
        if (link.hostname === window.location.hostname && !link.hash && link.target !== '_blank') {
            link.addEventListener('click', (e) => {
                if (!e.ctrlKey && !e.shiftKey && !e.metaKey) {
                    window.startProgress();
                }
            });
        }
    });
}

// Dynamic Active Links
function initActiveLinks() {
    const currentPath = window.location.pathname;
    document.querySelectorAll('.nav-link').forEach(link => {
        const href = link.getAttribute('href');
        if (currentPath === href || (currentPath === '/' && href === '/home')) {
            link.classList.add('active');
        } else {
            link.classList.remove('active');
        }
    });
}

// Back to Top Button
function initBackToTop() {
    const btn = document.createElement('div');
    btn.id = 'backToTop';
    btn.innerHTML = '↑';
    btn.title = 'Back to Top';
    document.body.appendChild(btn);

    window.addEventListener('scroll', () => {
        if (window.pageYOffset > 300) {
            btn.classList.add('visible');
        } else {
            btn.classList.remove('visible');
        }
    });

    btn.addEventListener('click', () => {
        window.scrollTo({ top: 0, behavior: 'smooth' });
    });
}

// Page Transitions
function initPageTransitions() {
    const overlay = document.createElement('div');
    overlay.className = 'page-transition-overlay';
    document.body.appendChild(overlay);

    // Fade in on load
    window.addEventListener('pageshow', () => {
        overlay.classList.remove('active');
    });

    // Fade out on navigation
    document.querySelectorAll('a').forEach(link => {
        if (link.hostname === window.location.hostname && !link.hash && link.target !== '_blank') {
            link.addEventListener('click', (e) => {
                if (!e.ctrlKey && !e.shiftKey && !e.metaKey) {
                    overlay.classList.add('active');
                }
            });
        }
    });
}

// Breadcrumbs
function initBreadcrumbs() {
    const container = document.querySelector('.breadcrumb-container');
    if (!container) return;

    const path = window.location.pathname.split('/').filter(p => p);
    if (path.length === 0 || path[0] === 'home') return;

    const breadcrumb = document.createElement('div');
    breadcrumb.className = 'breadcrumb container';
    
    let html = '<a href="/home">Home</a>';
    let currentPath = '';
    
    path.forEach((p, index) => {
        currentPath += '/' + p;
        let name = p.charAt(0).toUpperCase() + p.slice(1).replace(/-/g, ' ');
        if (p === 'recommendations') name = 'Get SHC';
        if (p === 'map') name = 'Soil Map';
        
        html += `<span class="separator">/</span>`;
        if (index === path.length - 1) {
            html += `<span>${name}</span>`;
        } else {
            html += `<a href="${currentPath}">${name}</a>`;
        }
    });

    breadcrumb.innerHTML = html;
    container.appendChild(breadcrumb);
}
