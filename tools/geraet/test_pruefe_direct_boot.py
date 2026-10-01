"""Haelt fest, dass pruefe_direct_boot.py nur SCHARFE Wecker zaehlt.

Aufruf:
    python -m unittest discover -s tools/geraet -p "test_*.py"
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from pruefe_direct_boot import armierte_aus_dumpsys  # noqa: E402

PAKET = "com.github.f1rlefanz.cf_alarmfortimeoffice"

DUMPSYS = f"""Current Alarm Manager state:
  Settings:
    min_futurity=+5s0ms
  2 pending alarms:
    RTC_WAKEUP #1: Alarm{{abc type 0 origWhen 1 {PAKET}}}
      tag=*walarm*:{PAKET}.ENHANCED_ALARM_111
    RTC_WAKEUP #0: Alarm{{def type 0 origWhen 2 {PAKET}}}
      tag=*walarm*:{PAKET}.ENHANCED_ALARM_-222
  LazyAlarmStore stats:
    x
  Removal history:
    {PAKET}:
      Removed: tag=*walarm*:{PAKET}.ENHANCED_ALARM_333 reason=alarm_cancelled
  Alarm Stats:
    +5ms 0 wakes 1 alarms: *walarm*:{PAKET}.ENHANCED_ALARM_444
"""


class ArmierteWecker(unittest.TestCase):

    def test_nur_der_pending_abschnitt_zaehlt(self):
        self.assertEqual(
            {"ENHANCED_ALARM_111", "ENHANCED_ALARM_-222"},
            armierte_aus_dumpsys(DUMPSYS),
        )

    def test_abgebrochene_wecker_aus_der_historie_zaehlen_nicht(self):
        """Der Fehlalarm vom 01.10.2026: Historie vor dem Neustart, danach geleert."""
        gefunden = armierte_aus_dumpsys(DUMPSYS)
        self.assertNotIn("ENHANCED_ALARM_333", gefunden)
        self.assertNotIn("ENHANCED_ALARM_444", gefunden)

    def test_ohne_pending_abschnitt_leer(self):
        self.assertEqual(set(), armierte_aus_dumpsys("Current Alarm Manager state:\n  Settings:\n"))


if __name__ == "__main__":
    unittest.main()
