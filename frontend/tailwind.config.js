/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        // Risk levels
        'risk-safe': '#10B981',
        'risk-caution': '#F59E0B',
        'risk-warning': '#F97316',
        'risk-alarm': '#EF4444',
        // Maritime
        'land': '#374151',
        'water-deep': '#111827',
        'water-shallow': '#1E3A8A',
        'ghost': '#9CA3AF',
      },
    },
  },
  plugins: [],
}
