/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'Noto Sans SC', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'SF Mono', 'ui-monospace', 'monospace'],
      },
      colors: {
        'risk-safe': 'oklch(0.76 0.11 158)',
        'risk-caution': 'oklch(0.82 0.12 85)',
        'risk-warning': 'oklch(0.72 0.15 55)',
        'risk-alarm': 'oklch(0.66 0.18 22)',
        accent: 'oklch(0.62 0.13 220)',
        'water-deep': '#142340',
        'water-light': '#cfe3ef',
        'land-dark': '#242f3e',
        'land-light': '#e4e0d2',
      },
      borderRadius: {
        '2xl': '1rem',
        '3xl': '1.5rem',
        '4xl': '2rem',
      },
      animation: {
        'soft-pulse': 'softPulse 2.6s ease-in-out infinite',
        'soft-rise': 'softRise 0.5s cubic-bezier(0.16, 1, 0.3, 1) both',
      },
    },
  },
  plugins: [],
};
