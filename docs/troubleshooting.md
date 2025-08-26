---
layout: default
title: Troubleshooting Guide
---

{% include navigation.md %}

# 🆘 Troubleshooting Guide

## 🔍 Quick Diagnosis

**Before starting**: Update to the latest app version and restart your device.

### ⚡ Most Common Issues (90% of cases)

1. **Alarms not working** → Disable battery optimization
2. **Calendar not syncing** → Check internet connection & permissions  
3. **Hue lights not responding** → Verify same WiFi network
4. **Android 14+ issues** → Enable "Alarms & Reminders" permission

---

## 🚨 Alarm Issues

### OnePlus Devices (ColorOS/OxygenOS)

**Problem:** Alarms don't trigger reliably

**Solution Steps:**
1. **Settings** → **Battery** → **Battery Optimization**
2. Search for **CF Alarm** → Select **Don't Optimize**
3. **Settings** → **Apps** → **CF Alarm** → **Battery**
4. Enable **Allow background activity**
5. Enable **Allow auto-launch**

> 💡 **OnePlus Tip**: Also check **Settings** → **Privacy Permissions** → **Startup Manager** → Enable CF Alarm

### Samsung Devices (One UI 4.0+)

**Problem:** App gets killed in background

**Solution Steps:**
1. **Settings** → **Apps** → **CF Alarm** → **Battery**
2. Select **Unrestricted** battery usage
3. **Settings** → **Device Care** → **Battery** → **App Power Management**
4. Add **CF Alarm** to **Never sleeping apps**
5. **Settings** → **Apps** → **CF Alarm** → **Permissions**
6. Ensure **"Appear on top"** is enabled

**Samsung Secure Folder:** If using work profile, configure separately in Secure Folder.

### Xiaomi/MIUI Devices

**Problem:** MIUI's aggressive background management

**Solution Steps:**
1. **Settings** → **Apps** → **Manage Apps** → **CF Alarm**
2. Enable **Autostart**
3. **Battery & Performance** → **Battery** → **App Battery Saver**
4. Set CF Alarm to **No restrictions**
5. **Other Permissions** → Enable **Display pop-up windows**

**MIUI 13+**: Also check **Settings** → **Privacy Protection** → **Special App Access**

### Huawei/Honor Devices (EMUI/MagicUI)

**Problem:** Ultra-aggressive power management

**Solution Steps:**
1. **Settings** → **Battery** → **Launch**
2. Find **CF Alarm** → **Manage manually**
3. Enable ALL three options:
   - **Auto-launch** ✅
   - **Secondary launch** ✅  
   - **Run in background** ✅
4. **Settings** → **Apps & Notifications** → **CF Alarm** → **Battery**
5. Select **Don't optimize**

---

## 📅 Calendar Sync Problems

### Authentication Issues

#### "Sign in required" Error
**Cause:** OAuth token expired or revoked

**Solution:**
1. Open CF Alarm → **Settings** → **Account**
2. Tap **Sign Out** → **Sign In Again**
3. Grant all requested permissions
4. Select correct work calendar

#### Wrong Calendar Selected  
**Cause:** Multiple calendars available

**Solution:**
1. **Settings** → **Calendar Selection**
2. Choose your work calendar (not personal)
3. Verify calendar contains work events
4. **Sync** button to refresh

#### Google Account Restrictions
**Cause:** Google Workspace admin restrictions

**Solution:**
1. Contact your IT administrator
2. Request OAuth app approval for CF Alarm
3. Alternatively: Use personal Google account for work calendar access

### Network & Sync Issues

#### Calendar Events Not Loading
**Diagnostics:**
```
Settings → About → Connection Test
```

**Solutions:**
1. **WiFi Issues**: Switch to mobile data temporarily
2. **Proxy/VPN**: Disable temporarily to test
3. **Corporate Firewall**: Request whitelisting for `*.googleapis.com`
4. **Clear Cache**: Settings → Apps → CF Alarm → Storage → Clear Cache

#### Partial Calendar Sync
**Cause:** Large calendar with many events

**Solution:**
1. **Settings** → **Calendar Sync** → **Date Range**  
2. Reduce to **30 days** instead of 90 days
3. **Manual Sync** to test
4. Gradually increase range if working

---

## 💡 Philips Hue Issues

### Bridge Discovery Problems

#### Bridge Not Found
**Diagnostics:**
1. Both devices on same WiFi network? ✅
2. Bridge power LED solid blue? ✅
3. Hue app works on same device? ✅

**Solutions:**
1. **Network Reset**: Restart WiFi router
2. **Bridge Reset**: Unplug bridge 30 seconds, reconnect
3. **Manual IP**: Settings → Hue → Manual Bridge IP
4. **UPnP Check**: Enable UPnP on router (if disabled)

#### Link Button Timeout
**Problem:** 30-second pairing window too short

**Solution:**
1. **Prepare First**: Open CF Alarm → Settings → Hue → Add Bridge
2. **Press Link Button** on bridge (LED blinks)
3. **Immediately** tap "Search" in app
4. **Multiple Attempts**: Bridge allows 30 attempts in 10 minutes

### Light Control Issues

#### Lights Not Responding
**Diagnostics in Hue App:**
1. Can you control lights manually? ✅
2. Are lights in correct room/group? ✅
3. Bridge firmware updated? ✅

**CF Alarm Solutions:**
1. **Settings** → **Hue** → **Refresh Lights**
2. **Test Lights**: Tap test button for each light
3. **Re-pair Bridge**: Remove and add bridge again
4. **Network Stability**: Check WiFi signal strength

#### Sunrise Simulation Not Working
**Common Causes:**
- Lights already on (room not dark)
- Light bulbs don't support color temperature
- Bridge version 1 (not supported)

**Solutions:**
1. **Room Conditions**: Ensure room is dark when alarm starts
2. **Bulb Compatibility**: Use color/white ambiance bulbs
3. **Timing Test**: Set test alarm 5 minutes ahead
4. **Manual Override**: Settings → Hue → Force Sunrise Always

---

## 🔔 Notification & Permission Issues

### Android 13/14 Specific Issues

#### Missing "Alarms & Reminders" Permission
**Problem:** New Android 14+ permission not granted

**Solution:**
1. **Settings** → **Apps** → **CF Alarm** → **Permissions**
2. **Special App Access** → **Alarms & Reminders**
3. Enable **CF Alarm** ✅
4. **Restart app** to apply changes

#### Notification Categories Disabled
**Problem:** Granular notification control

**Solution:**
1. **Settings** → **Apps** → **CF Alarm** → **Notifications**
2. Enable **all notification categories**:
   - Alarm Notifications ✅
   - Calendar Sync ✅
   - Hue Control ✅
   - Error Messages ✅

### Do Not Disturb Issues

#### Alarms Silenced by DND
**Problem:** DND blocking alarm sounds

**Solution:**
1. **Settings** → **Sound** → **Do Not Disturb**
2. **Exceptions** → **Apps** → Add **CF Alarm**
3. **OR** **Alarms** → **Always Allow** ✅

#### Scheduled DND Conflicts
**Problem:** Work DND schedule conflicts with alarm times

**Solution:**
1. **DND Schedule**: Exclude early morning hours (5-7 AM)
2. **CF Alarm Priority**: Settings → Notifications → **Allow Override DND**
3. **Volume Override**: Settings → Sounds → **Alarm Volume Override**

---

## ⚡ Performance & Battery Issues

### High Battery Usage

#### Background Activity Analysis
**Check Usage:**
1. **Settings** → **Battery** → **CF Alarm**
2. Review **Background Activity** percentage
3. **Expected**: 2-5% daily usage
4. **Excessive**: 10%+ indicates issues

**Optimization:**
1. **Reduce Sync Frequency**: Settings → Calendar → Sync Interval → 30 minutes
2. **Disable Hue Features**: If not needed
3. **Location Services**: Disable if not using location features
4. **Background Refresh**: Settings → Apps → CF Alarm → Battery → Optimize

### App Performance Issues

#### Slow Startup / UI Lag
**Causes & Solutions:**
1. **Low Storage**: Ensure 500MB+ free space
2. **Memory Pressure**: Close background apps
3. **Cache Buildup**: Settings → Apps → CF Alarm → Storage → Clear Cache
4. **Database Size**: Settings → Advanced → Reset Calendar Cache

#### Crashes on Startup
**Emergency Recovery:**
1. **Force Stop**: Settings → Apps → CF Alarm → Force Stop
2. **Clear Cache**: (Don't use Clear Data yet)
3. **Restart Device**
4. **Last Resort**: Clear Data (will reset all settings)

**Debug Info Collection:**
1. **Before Clearing Data**: Settings → Support → Export Logs
2. **Send Logs**: Create GitHub issue with log attachment

---

## 🔧 Advanced Troubleshooting

### Developer Options Debug

#### Enable Detailed Logging
1. **Settings** → **About** → Tap **Version** 7 times
2. **Developer Options** unlocked
3. **CF Alarm Settings** → **Developer Options** → **Verbose Logging** ✅
4. **Reproduce Issue**
5. **Settings** → **Support** → **Export Debug Logs**

#### Network Traffic Analysis
**For connection issues:**
1. **Developer Options** → **Network Logging** ✅
2. **Reproduce sync issue**
3. **Export Logs** → Check for HTTP errors
4. **Common Issues**: 403 (permissions), 429 (rate limits), 500 (server errors)

### Factory Reset (Nuclear Option)

**When all else fails:**

⚠️ **WARNING**: This deletes ALL app data including:
- All alarm configurations
- Google account connection  
- Philips Hue pairings
- Custom settings

**Steps:**
1. **Backup First**: Settings → Backup → Export Settings File
2. **Settings** → **Apps** → **CF Alarm** → **Storage**
3. **Clear Data** → **Delete** → **OK**
4. **Restart App** → Go through setup again
5. **Restore**: Import settings file (if compatible)

---

## 📱 Device-Specific Known Issues

### Flagship Devices

#### Google Pixel Issues
- **Adaptive Battery**: Can be overly aggressive
- **Solution**: Settings → Battery → Adaptive preferences → CF Alarm → Unrestricted

#### Samsung Galaxy Ultra Series
- **Enhanced Processing**: Can delay alarm processing
- **Solution**: Developer Options → Disable Window Animation Scale

### Budget/Mid-Range Issues

#### Insufficient RAM (2GB-3GB devices)
- **Symptoms**: App killed frequently, slow performance
- **Solutions**: 
  - Close all unnecessary apps before sleep
  - Disable live wallpapers
  - Reduce calendar sync frequency to 60 minutes

#### Storage Issues (32GB devices)
- **Symptoms**: Database errors, sync failures
- **Solutions**:
  - Maintain 1GB+ free storage
  - Move photos/videos to cloud storage
  - Uninstall unused apps

---

## 🆘 Emergency Contact & Support

### Before Contacting Support

**Gather This Information:**
- **Device**: Brand, model, Android version
- **App Version**: Settings → About → Version
- **Issue Type**: Alarm, Calendar, Hue, Performance
- **Steps Tried**: List troubleshooting steps attempted
- **Frequency**: Always, sometimes, specific conditions
- **Error Messages**: Exact text (screenshot preferred)

### Support Channels (Response Time)

1. **🔥 Critical Alarm Failures** (4 hours):
   - **Email**: emergency@cf-alarm.app
   - **Include**: Device info, alarm time missed, impact

2. **🐛 Bug Reports** (24-48 hours):
   - **GitHub Issues**: [Report Bug](https://github.com/f1rlefanz/cf-alarmfortimeoffice/issues/new?template=bug_report.md)
   - **Include**: Logs, screenshots, reproduction steps

3. **❓ General Questions** (3-5 days):
   - **GitHub Discussions**: [Ask Question](https://github.com/f1rlefanz/cf-alarmfortimeoffice/discussions)
   - **Community Support**: Other users can help

4. **💼 Enterprise Support** (4 hours business days):
   - **Email**: enterprise@cf-alarm.app
   - **Available**: For organizations with 10+ devices

### Log Export Instructions

**For Technical Issues:**
1. **Settings** → **Support** → **Prepare Support Package**
2. **Include**:
   - System information ✅
   - Recent logs (24 hours) ✅
   - Network diagnostics ✅
   - Permission status ✅
3. **Export** → Share via email or GitHub issue
4. **Privacy**: Logs contain NO personal calendar data

---

## ✅ Success Rate by Issue Type

Based on community feedback:

| Issue Type | Self-Resolution Rate | Avg. Resolution Time |
|------------|---------------------|----------------------|
| Battery Optimization | **95%** | 5-10 minutes |
| Calendar Permissions | **90%** | 2-5 minutes |
| Hue Bridge Setup | **85%** | 10-15 minutes |
| Android 14+ Permissions | **90%** | 3-7 minutes |
| Performance Issues | **75%** | 15-30 minutes |
| Device-Specific Problems | **80%** | 20-45 minutes |

**Most issues are resolved within 15 minutes following this guide!** 🎯

---

📚 **Related Documentation:**
- [🏠 Privacy Policy](/) 
- [⚙️ Advanced Setup Guide](advanced-setup)
- [💻 Developer Documentation](developer-guide)

### 📅 Calendar Sync Issues

#### Google Calendar Not Loading
**Problem:** Calendar events don't appear
**Solution:**
1. Check **internet connection**
2. Go to **Settings** → **Account** → **Sign Out** → **Sign In** again
3. Verify **calendar permissions** in Android settings
4. Clear app cache: **Settings** → **Apps** → **CF Alarm** → **Storage** → **Clear Cache**

#### Wrong Calendar Selected
**Problem:** Events from wrong calendar showing
**Solution:**
1. Open app → **Settings** → **Calendar Selection**
2. Choose the correct work calendar
3. **Sync** will update automatically

#### Authentication Expired
**Problem:** "Authentication required" error
**Solution:**
1. **Settings** → **Account** → **Re-authenticate**
2. Grant all required permissions
3. If persistent: revoke app permissions in Google Account settings and re-add

### 💡 Philips Hue Problems

#### Bridge Not Found
**Problem:** Cannot discover Hue Bridge
**Solution:**
1. Ensure phone and bridge are on **same WiFi network**
2. Check bridge **power connection**
3. Press bridge **Link Button** and retry within 30 seconds
4. Restart WiFi router if necessary

#### Lights Not Responding
**Problem:** Hue lights don't react to alarms
**Solution:**
1. Test lights in **Philips Hue app** first
2. Check **light selection** in CF Alarm settings
3. Verify bridge **firmware is updated**
4. Re-pair bridge in CF Alarm settings

#### Hue Bridge v1 Issues
**Problem:** Old bridge not supported
**Solution:**
- **Hue Bridge v1 is not supported** (discontinued 2020)
- **Upgrade to Bridge v2** or newer required
- Check bridge model on bottom label

### 🔔 Notification Issues

#### Android 13/14 Permission Issues
**Problem:** Notifications not showing
**Solution:**
1. **Settings** → **Apps** → **CF Alarm** → **Permissions**
2. Enable **Notifications**
3. For Android 14: Enable **Alarms & Reminders** permission
4. Check **Do Not Disturb** settings

#### Notification Sound Not Playing
**Problem:** Silent notifications
**Solution:**
1. Check **device volume** (not just media volume)
2. **Settings** → **Apps** → **CF Alarm** → **Notifications**
3. Verify **notification category** settings
4. Check **Do Not Disturb** exceptions

### ⚡ Performance Issues

#### App Crashes on Startup
**Problem:** App closes immediately
**Solution:**
1. **Restart device**
2. **Clear app cache**: Settings → Apps → CF Alarm → Storage → Clear Cache
3. **Update app** to latest version
4. If persistent: **Clear app data** (will reset settings)

#### High Battery Usage
**Problem:** App consuming too much battery
**Solution:**
1. Check **background refresh** frequency in settings
2. **Reduce calendar sync** interval
3. **Disable Hue features** if not needed
4. Review **alarm frequency** - too many alarms increase usage

#### Slow Performance
**Problem:** App responds slowly
**Solution:**
1. **Restart app**
2. **Clear cache** as above
3. Check available **device storage** (need 100MB+ free)
4. **Reduce calendar history** in settings

### 🔧 Advanced Troubleshooting

#### Enable Debug Logging
1. **Settings** → **About** → Tap version 7 times
2. **Developer Options** → **Enable Detailed Logging**
3. Reproduce issue
4. **Settings** → **Support** → **Export Logs**

#### Network Connectivity Issues
1. **WiFi**: Ensure stable connection
2. **Mobile Data**: Check data permissions for app
3. **Proxy/VPN**: May interfere with Google API calls
4. **Firewall**: Corporate networks may block OAuth

#### Factory Reset (Last Resort)
**Warning: This will delete all app data**
1. **Settings** → **Apps** → **CF Alarm** → **Storage**
2. **Clear Data** → **Delete**
3. **Restart app** and reconfigure

### 📱 Device-Specific Issues

#### Huawei/Honor Devices
- **Battery optimization** particularly aggressive
- **Settings** → **Battery** → **Launch** → **CF Alarm** → **Manage manually**
- Enable all three options: **Auto-launch**, **Secondary launch**, **Run in background**

#### Oppo/Realme Devices
- **Settings** → **Battery** → **Battery Optimization** → **CF Alarm** → **Don't optimize**
- **Settings** → **Privacy Permissions** → **Startup Manager** → Enable **CF Alarm**

#### Nokia Devices
- Similar to stock Android, but check **Adaptive Battery** settings
- **Settings** → **Battery** → **Adaptive preferences** → **CF Alarm** → **Unrestricted**

### 🆘 Still Need Help?

#### Before Contacting Support
1. **Update** to latest app version
2. **Try** basic troubleshooting above
3. **Note** your device model and Android version
4. **Export logs** if available

#### Contact Options
- **GitHub Issues**: [Report Bug](https://github.com/f1rlefanz/cf-alarmfortimeoffice/issues)
- **Email Support**: support@cf-alarm.app
- **Community**: [GitHub Discussions](https://github.com/f1rlefanz/cf-alarmfortimeoffice/discussions)

#### What to Include
- **Device model** and **Android version**
- **App version**
- **Steps to reproduce** the issue
- **Screenshots** if relevant
- **Error messages** (exact text)
- **Log files** (if available)

---

**Most issues can be resolved with proper Android permissions and battery optimization settings!** 🔋
