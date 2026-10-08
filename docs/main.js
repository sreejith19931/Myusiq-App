/**
 * Myusiq Website Interactive Functionality
 * Features: Theme Sandbox, Interactive EQ Visualizer, Janitor Before/After Toggle,
 * Screenshot Lightbox, Scroll Animations & Dark/Light Mode.
 */

function closeBanner() {
    const banner = document.getElementById('announcement-banner');
    if (banner) banner.style.display = 'none';
}

// --- 1. Theme Sandbox & Dark/Light Mode ---
const defaultThemes = [
    { name: "Purple Ambient", primary: "#A78BFA", container: "#2E1065" },
    { name: "Sky Pulse", primary: "#38BDF8", container: "#0F172A" },
    { name: "Emerald Rhythm", primary: "#43A047", container: "#1B5E20" },
    { name: "Crimson Flame", primary: "#E53935", container: "#B71C1C" },
    { name: "Sunset Gold", primary: "#FB8C00", container: "#E65100" }
];

let isPickerOpen = false;

function toggleThemePicker() {
    const widget = document.getElementById('theme-widget');
    const btn = document.getElementById('toggle-theme-picker');
    if (!widget || !btn) return;

    isPickerOpen = !isPickerOpen;
    if (isPickerOpen) {
        widget.style.display = 'block';
        widget.classList.add('animate-fade-in');
        btn.innerHTML = '🎨 Hide Theme Sandbox';
        btn.style.borderColor = 'var(--primary)';
    } else {
        widget.style.display = 'none';
        btn.innerHTML = '🎨 Test Dynamic Themes';
        btn.style.borderColor = 'var(--outline)';
        // Reset to default theme
        changeTheme(defaultThemes[0].primary, defaultThemes[0].container, document.querySelector('.color-dot'));
    }
}

function changeTheme(primary, container, el) {
    document.documentElement.style.setProperty('--primary', primary);
    document.documentElement.style.setProperty('--primary-container', container);

    document.querySelectorAll('.color-dot').forEach(dot => dot.classList.remove('active'));
    if (el) el.classList.add('active');

    // Subtle scale feedback on active cards
    const heroFrame = document.querySelector('.phone-frame');
    if (heroFrame) {
        heroFrame.style.transform = 'scale(1.02)';
        setTimeout(() => { heroFrame.style.transform = 'none'; }, 200);
    }
}

// Dark / Light Mode Toggle
function initThemeToggle() {
    const toggleBtn = document.getElementById('theme-toggle');
    if (!toggleBtn) return;

    const savedTheme = localStorage.getItem('myusiq-color-scheme') || 'dark';
    if (savedTheme === 'light') {
        document.documentElement.classList.add('light-mode');
    }

    toggleBtn.addEventListener('click', () => {
        document.documentElement.classList.toggle('light-mode');
        const isLight = document.documentElement.classList.contains('light-mode');
        localStorage.setItem('myusiq-color-scheme', isLight ? 'light' : 'dark');
    });
}

// --- 2. Interactive Equalizer Visualizer ---
function initEqualizer() {
    const eqSliders = document.querySelectorAll('.eq-slider');
    const path = document.getElementById('eq-path');
    if (!eqSliders.length || !path) return;

    function updateCurve() {
        const values = Array.from(eqSliders).map(s => parseInt(s.value));
        // Map 5 band values (-10 to +10) to SVG Y coordinates (height 120, center 60)
        // Values: band1 (x=30), band2 (x=100), band3 (x=170), band4 (x=240), band5 (x=310)
        const coords = values.map((val, idx) => {
            const x = 30 + idx * 70;
            const y = 60 - (val * 4); // +10 dB -> y=20, -10 dB -> y=100
            return { x, y };
        });

        // Construct smooth cubic bezier path
        let d = `M 0 60 C 15 60, ${coords[0].x - 15} ${coords[0].y}, ${coords[0].x} ${coords[0].y}`;
        for (let i = 0; i < coords.length - 1; i++) {
            const curr = coords[i];
            const next = coords[i + 1];
            const cp1x = curr.x + 35;
            const cp2x = next.x - 35;
            d += ` C ${cp1x} ${curr.y}, ${cp2x} ${next.y}, ${next.x} ${next.y}`;
        }
        d += ` C ${coords[4].x + 15} ${coords[4].y}, 325 60, 340 60`;

        path.setAttribute('d', d);

        // Update gain badges
        eqSliders.forEach((s, idx) => {
            const badge = document.getElementById(`eq-val-${idx}`);
            if (badge) {
                const val = parseInt(s.value);
                badge.innerText = (val > 0 ? `+${val}` : `${val}`) + ' dB';
                badge.style.color = val !== 0 ? 'var(--primary)' : 'var(--on-surface-variant)';
            }
        });
    }

    eqSliders.forEach(slider => {
        slider.addEventListener('input', updateCurve);
    });

    updateCurve();
}

// Preset loader for Equalizer
function applyEqPreset(preset) {
    const presets = {
        bass: [8, 6, 2, 0, 1],
        vocal: [-2, 1, 6, 4, 1],
        flat: [0, 0, 0, 0, 0],
        electronic: [6, 4, -1, 5, 7]
    };

    const vals = presets[preset] || presets.flat;
    const sliders = document.querySelectorAll('.eq-slider');
    sliders.forEach((s, idx) => {
        s.value = vals[idx];
    });

    const path = document.getElementById('eq-path');
    if (path) {
        // Trigger event
        sliders[0].dispatchEvent(new Event('input'));
    }

    // Active button styling
    document.querySelectorAll('.preset-btn').forEach(btn => btn.classList.remove('active'));
    const clickedBtn = document.querySelector(`.preset-btn[data-preset="${preset}"]`);
    if (clickedBtn) clickedBtn.classList.add('active');
}

// --- 3. Library Janitor Before/After Toggle ---
let isJanitorFixed = false;
function toggleJanitorView() {
    const viewContainer = document.getElementById('janitor-demo');
    const toggleBtn = document.getElementById('janitor-toggle-btn');
    const titleEl = document.getElementById('janitor-title');
    const artistEl = document.getElementById('janitor-artist');
    const artEl = document.querySelector('.janitor-art');

    if (!viewContainer || !toggleBtn) return;

    isJanitorFixed = !isJanitorFixed;
    if (isJanitorFixed) {
        viewContainer.classList.add('repaired');
        if (titleEl) titleEl.innerText = 'Midnight City (Remastered)';
        if (artistEl) {
            artistEl.innerText = 'M83 • Hurry Up, We\'re Dreaming (High-Res Art Fetched)';
            artistEl.style.color = 'var(--primary)';
        }
        if (artEl) {
            artEl.innerHTML = '🎨';
            artEl.style.background = 'linear-gradient(135deg, var(--primary), var(--secondary))';
        }
        toggleBtn.innerText = '↺ Show Original Untagged Track';
        toggleBtn.style.background = 'var(--primary)';
        toggleBtn.style.color = 'var(--on-primary)';
    } else {
        viewContainer.classList.remove('repaired');
        if (titleEl) titleEl.innerText = 'Track_01_Unknown.mp3';
        if (artistEl) {
            artistEl.innerText = 'Unknown Artist • Missing Artwork';
            artistEl.style.color = 'var(--on-surface-variant)';
        }
        if (artEl) {
            artEl.innerHTML = '🎵';
            artEl.style.background = 'var(--outline)';
        }
        toggleBtn.innerText = '✨ Auto-Fix & Fetch Album Art';
        toggleBtn.style.background = 'var(--surface-container-high)';
        toggleBtn.style.color = 'var(--on-surface)';
    }
}

// --- 4. Screenshot Lightbox Modal ---
function openLightbox(imgSrc, captionText) {
    const modal = document.getElementById('lightbox-modal');
    const modalImg = document.getElementById('lightbox-img');
    const modalCaption = document.getElementById('lightbox-caption');
    if (!modal || !modalImg) return;

    modalImg.src = imgSrc;
    if (modalCaption) modalCaption.innerText = captionText || '';
    modal.style.display = 'flex';
    document.body.style.overflow = 'hidden';
}

function closeLightbox() {
    const modal = document.getElementById('lightbox-modal');
    if (!modal) return;
    modal.style.display = 'none';
    document.body.style.overflow = 'auto';
}

// --- 5. Scroll Animations & Navbar Scroll ---
function initScrollAnimations() {
    const observerOptions = {
        threshold: 0.15,
        rootMargin: '0px 0px -50px 0px'
    };

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('active');
            }
        });
    }, observerOptions);

    document.querySelectorAll('.reveal').forEach(el => observer.observe(el));

    // Navbar Scroll Blur Effect
    const navbar = document.getElementById('navbar');
    if (navbar) {
        window.addEventListener('scroll', () => {
            if (window.scrollY > 40) {
                navbar.classList.add('scrolled');
            } else {
                navbar.classList.remove('scrolled');
            }
        });
    }
}

// Filter screenshots gallery
function filterGallery(category, btnEl) {
    document.querySelectorAll('.gallery-tab').forEach(t => t.classList.remove('active'));
    if (btnEl) btnEl.classList.add('active');

    const cards = document.querySelectorAll('.screenshot-card');
    cards.forEach(card => {
        const cardCat = card.getAttribute('data-category');
        if (category === 'all' || cardCat === category) {
            card.style.display = 'block';
            card.classList.add('animate-fade-in');
        } else {
            card.style.display = 'none';
        }
    });
}

// DOM Ready Initialization
document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();
    initEqualizer();
    initScrollAnimations();

    // ESC key closes lightbox
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeLightbox();
    });
});
