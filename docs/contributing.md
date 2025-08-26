---
layout: default
title: Contributing Guide
---

{% include navigation.md %}

# 🤝 Contributing to CF Alarm for Time Office

## Welcome Contributors! 🎉

We're excited that you want to contribute to CF Alarm for Time Office! This document provides guidelines and information for contributors.

## Quick Start

1. **Fork** the repository
2. **Clone** your fork locally
3. **Create** a feature branch
4. **Make** your changes
5. **Test** thoroughly
6. **Submit** a pull request

## Development Setup

### Prerequisites

- **Android Studio** 2025.1.1 (Narwhal) or newer
- **JDK** 17 (LTS)
- **Android SDK** API 36
- **Kotlin** 2.1.0+

### Getting Started

```bash
# Clone your fork
git clone https://github.com/YOUR_USERNAME/cf-alarmfortimeoffice.git
cd cf-alarmfortimeoffice

# Create keystore.properties (see Security Setup)
touch keystore.properties

# Open in Android Studio
# Build → Sync Project with Gradle Files
```

## 🔐 Security Setup

Create `keystore.properties` in project root:

```properties
storeFile=./debug.keystore
storePassword=android
keyAlias=androiddebugkey  
keyPassword=android
googleWebClientId=YOUR_GOOGLE_CLIENT_ID
```

## Code Guidelines

### Architecture
- Follow **MVVM + Clean Architecture**
- Use **Repository Pattern** for data access
- Implement **UseCase classes** for business logic
- Apply **Dependency Injection** with Hilt

### Code Style
- Follow **Kotlin Coding Conventions**
- Use **meaningful variable names**
- Write **comprehensive comments**
- Keep functions **small and focused**

### Testing
- Write **unit tests** for business logic
- Add **integration tests** for repositories
- Test **edge cases** and error scenarios
- Maintain **test coverage** above 80%

## What We're Looking For

### 🐛 Bug Reports
- Clear **reproduction steps**
- **Device information** (Android version, model)
- **Screenshots** if applicable
- **Logs** when relevant

### ✨ Feature Requests
- **Detailed description** of the feature
- **Use case** explanation
- **Mockups** or wireframes (if UI related)
- **Implementation suggestions**

### 🔧 Code Contributions
- **Clean, well-tested code**
- **Documentation updates**
- **Security best practices**
- **Performance optimizations**

## Development Areas

### High Priority
- **Wear OS Integration**
- **Music Service Integration**
- **Battery Optimization**
- **Accessibility Improvements**

### Medium Priority
- **UI/UX Enhancements**
- **Additional Smart Home Platforms**
- **Advanced Notification Features**
- **Widget Development**

### Documentation
- **API Documentation**
- **User Guides**
- **Troubleshooting Guides**
- **Translation (i18n)**

## Pull Request Process

### Before Submitting
1. **Test** your changes thoroughly
2. **Update** documentation if needed
3. **Add** tests for new functionality
4. **Follow** our code style guidelines
5. **Rebase** on latest main branch

### PR Requirements
- **Clear title** describing the change
- **Detailed description** of what was changed
- **Link to related issues**
- **Screenshots** for UI changes
- **Test results** included

### Review Process
1. **Automated checks** must pass
2. **Code review** by maintainers
3. **Testing** on multiple devices
4. **Approval** and merge

## Community Guidelines

### Be Respectful
- **Constructive feedback** only
- **Welcoming** to new contributors
- **Professional** communication
- **Inclusive** language

### Be Helpful
- **Share knowledge** and expertise
- **Help** other contributors
- **Document** your solutions
- **Respond promptly** to feedback

## Getting Help

### Documentation
- [Developer Guide](developer-guide)
- [API Reference](api-reference)
- [Troubleshooting](troubleshooting)

### Communication
- **GitHub Issues** for bugs and features
- **GitHub Discussions** for general questions
- **Email** for security-related issues

## Recognition

Contributors will be:
- **Listed** in our Contributors section
- **Credited** in release notes
- **Invited** to beta testing programs
- **Welcomed** to maintainer team (long-term contributors)

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

---

**Thank you for contributing to CF Alarm for Time Office!** 🚀
