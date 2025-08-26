# CF Alarm Documentation Site

This directory contains the GitHub Pages documentation for CF Alarm for Time Office.

## 📖 Available Documentation

- **[🏠 Privacy Policy](https://f1rlefanz.github.io/CF-Alarm-for-TimeOffice/)** - DSGVO-compliant privacy policy
- **[🆘 Troubleshooting](https://f1rlefanz.github.io/CF-Alarm-for-TimeOffice/troubleshooting)** - Problem solving guide
- **[💻 Developer Guide](https://f1rlefanz.github.io/CF-Alarm-for-TimeOffice/developer-guide)** - Architecture & setup
- **[⚙️ Advanced Setup](https://f1rlefanz.github.io/CF-Alarm-for-TimeOffice/advanced-setup)** - Enterprise configuration
- **[🤝 Contributing](https://f1rlefanz.github.io/CF-Alarm-for-TimeOffice/contributing)** - How to contribute

## 🚀 GitHub Pages Setup

### Enabling GitHub Pages

1. **Repository Settings** → **Pages**
2. **Source**: Deploy from a branch
3. **Branch**: `main` / `docs` folder
4. **Save** - Site will be live in ~5 minutes

### File Structure

```
docs/
├── _config.yml              # Jekyll configuration
├── _includes/navigation.md   # Shared navigation
├── index.html               # Privacy Policy (home)
├── troubleshooting.md       # Troubleshooting guide
├── developer-guide.md       # Developer docs
├── advanced-setup.md        # Advanced config
├── contributing.md          # Contributing guide
└── README.md               # This documentation
```

## 📝 Content Guidelines

### Writing Style
- **Clear and actionable** - Help users solve problems
- **Well-structured** - Use headers, lists, code blocks
- **Mobile-friendly** - Many users on phones
- **Screenshot-rich** - Visual guides work better

### Adding New Pages

1. Create `.md` file in `/docs/`
2. Add Jekyll front matter:
   ```yaml
   ---
   layout: default
   title: Your Page Title
   ---
   
   {% include navigation.md %}
   ```
3. Update navigation in `_includes/navigation.md`
4. Test locally (optional) or commit directly

## 🔧 Maintenance Tasks

### Regular Updates
- [ ] Check all external links monthly
- [ ] Update screenshots when UI changes
- [ ] Sync with app version updates
- [ ] Monitor GitHub Issues for doc requests

### Performance
- Optimize images before uploading
- Keep pages focused and concise
- Link between related documentation
- Use descriptive URLs and titles

---

**Questions about documentation?** Create an issue in the main repository! 🚀
