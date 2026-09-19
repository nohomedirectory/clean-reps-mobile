"""Offline checks for permission-dialog selection; no ADB or device access."""

import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location(
    "android_rehearsal_ui", Path(__file__).with_name("android-rehearsal-ui.py")
)
DRIVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRIVER)


def hierarchy(*controls):
    tree = ET.Element("hierarchy")
    for control in controls:
        ET.SubElement(tree, "node", {
            "enabled": "true", "bounds": "[20,20][100,80]", **control,
        })
    return tree


class PermissionDialogSelectionTest(unittest.TestCase):
    def test_legacy_and_modern_android_dialogs(self):
        examples = (
            ("com.android.packageinstaller", "com.android.packageinstaller", "permission_allow_button"),
            ("com.google.android.packageinstaller", "com.android.packageinstaller", "permission_allow_button"),
            ("com.android.permissioncontroller", "com.android.permissioncontroller", "permission_allow_foreground_only_button"),
            ("com.google.android.permissioncontroller", "com.android.permissioncontroller", "permission_allow_one_time_button"),
        )
        for package, namespace, button in examples:
            with self.subTest(package=package, button=button):
                tree = hierarchy({"package": package, "resource-id": namespace + ":id/" + button})
                self.assertIs(DRIVER.requested_permission_allow_button(tree), tree[0])

    def test_unrelated_allow_controls_are_never_selected(self):
        examples = (
            {"package": DRIVER.PACKAGE, "resource-id": DRIVER.PACKAGE + ":id/permission_allow_button"},
            {"package": DRIVER.PACKAGE, "resource-id": "com.android.permissioncontroller:id/permission_allow_button"},
            {"package": "com.android.permissioncontroller", "resource-id": "other.app:id/permission_allow_button"},
            {"package": "com.android.permissioncontroller", "resource-id": "com.android.permissioncontroller:id/unrelated_permission_allow_button"},
            {"resource-id": "com.android.permissioncontroller:id/permission_allow_button"},
        )
        for control in examples:
            with self.subTest(control=control):
                self.assertIsNone(DRIVER.requested_permission_allow_button(hierarchy(control)))

    def test_disabled_allow_button_is_skipped(self):
        control = {
            "package": "com.android.permissioncontroller",
            "resource-id": "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
        }
        tree = hierarchy({**control, "enabled": "false"}, control)
        self.assertIs(DRIVER.requested_permission_allow_button(tree), tree[1])

    def test_always_allow_is_not_selected(self):
        tree = hierarchy({
            "package": "com.android.permissioncontroller",
            "resource-id": "com.android.permissioncontroller:id/permission_allow_always_button",
        })
        self.assertIsNone(DRIVER.requested_permission_allow_button(tree))


if __name__ == "__main__":
    unittest.main()
