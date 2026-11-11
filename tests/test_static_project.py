#!/usr/bin/env python3
"""Static project invariants only; NOT a build, runtime or encryption test."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
NS = {'android':'http://schemas.android.com/apk/res/android'}

class StaticChecks(unittest.TestCase):
    def test_xml_documents_parse(self):
        xml_files=list((ROOT/'app/src/main').rglob('*.xml'))
        self.assertGreaterEqual(len(xml_files),3)
        for path in xml_files:
            with self.subTest(path=path): ET.parse(path)
    def test_offline_manifest_and_wechat_scope(self):
        man = ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
        perms=[n.get('{%s}name'%NS['android']) for n in man.findall('uses-permission')]
        self.assertIn('android.permission.INTERNET',perms)
        services=man.findall('.//service')
        wechat=[s for s in services if s.get('{%s}name'%NS['android'])=='.WeChatReaderService']
        self.assertEqual(len(wechat),1)
        xml=(ROOT/'app/src/main/res/xml/wechat_accessibility.xml').read_text()
        self.assertIn('android:canRetrieveWindowContent="true"', xml)
    def test_encrypted_stores_and_export(self):
        src=(ROOT/'app/src/main/java/com/ounan/knowledge')
        notes=(src/'KnowledgeDb.kt').read_text()
        wechat=(src/'WeChatArchive.kt').read_text()
        self.assertIn('ArchiveCrypto.seal(pack(n))',notes)
        self.assertIn('PortableBackup.encrypt(',notes)
        self.assertIn('PortableBackup.decrypt(',notes)
        self.assertIn('ArchiveCrypto.seal(packed(entry))',wechat)
        self.assertIn('ArchiveCrypto.portableSeal(',wechat)
        self.assertIn('ArchiveCrypto.portableOpen(',wechat)
        self.assertFalse((src/'AiGatewayClient.kt').exists())
    def test_version_and_unique_id(self):
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('versionName = "0.5.1.2"',gradle)
        self.assertIn('versionCode = 28',gradle)
        self.assertIn('applicationId = "com.ounan.secondbrain"',gradle)
    def test_screenshot_open_for_testing(self):
        main = (ROOT/'app/src/main/java/com/ounan/knowledge/MainActivity.kt').read_text()
        self.assertNotIn('window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)', main)
        self.assertNotIn('window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)', main)
        self.assertNotIn('allowScreenshotsThisSession', main)
        self.assertIn('系统截图（测试开放）', main)
        self.assertIn('override fun onPause()', main)
    def test_legacy_trace_restored(self):
        overlay=(ROOT/'app/src/main/java/com/ounan/knowledge/SpiderOverlay.kt').read_text()
        self.assertIn('strokeWidth = ctx.resources.displayMetrics.density * 2.5f',overlay)
        self.assertIn('h.postDelayed(this, 175L)',overlay)
        self.assertIn('h.postDelayed(clear, 750L)',overlay)
        self.assertIn('🕷 ${count.coerceAtLeast(0)}',overlay)
    def test_custom_launcher_icon(self):
        manifest = (ROOT/'app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android:icon="@mipmap/ic_launcher"', manifest)
        for density in ['mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi']:
            for name in ['ic_launcher.png','ic_launcher_round.png']:
                path = ROOT/'app/src/main/res'/('mipmap-'+density)/name
                self.assertTrue(path.is_file())
        self.assertTrue((ROOT/'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml').is_file())
    def test_phone_cloud_build_recipe_present(self):
        self.assertTrue((ROOT/'.github/workflows/build-apk.yml').is_file())

if __name__=='__main__': unittest.main()
