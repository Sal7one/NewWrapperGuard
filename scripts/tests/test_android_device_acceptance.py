import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zlib

spec = importlib.util.spec_from_file_location('device_acceptance',Path(__file__).parents[1] / 'android-device-acceptance.py')
device = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device)


def chunk(kind,data):
    return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)


def image(pixel,*,width=320,height=480,channels=4,filter_mode=0):
    rows = []; above = bytes(width*channels)
    for y in range(height):
        raw = bytes(value for x in range(width) for value in pixel(x,y)[:channels])
        encoded = bytearray(raw)
        if filter_mode:
            for index,value in enumerate(raw):
                left = raw[index-channels] if index >= channels else 0
                up = above[index]
                corner = above[index-channels] if index >= channels else 0
                if filter_mode == 1: predictor = left
                elif filter_mode == 2: predictor = up
                elif filter_mode == 3: predictor = (left+up)//2
                else:
                    estimate = left+up-corner
                    predictor = min((left,up,corner),key=lambda candidate:abs(estimate-candidate))
                encoded[index] = (value-predictor)%256
        rows.append(bytes([filter_mode])+encoded); above = raw
    header = struct.pack('>IIBBBBB',width,height,8,6 if channels == 4 else 2,0,0,0)
    compressed = zlib.compress(b''.join(rows))
    middle = len(compressed)//2
    return (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',header)+chunk(b'IDAT',compressed[:middle])
            +chunk(b'IDAT',compressed[middle:])+chunk(b'IEND',b''))


def content(x,y):
    return (30,30,30,255) if 30 < x < 270 and 100 < y < 150 else (250,250,250,255)


class ScreenshotBoundaryTests(unittest.TestCase):
    def test_black_white_gray_and_transparent_frames_never_pass(self):
        for pixel in ((0,0,0,255),(255,255,255,255),(100,100,100,255),(0,0,0,0)):
            with self.subTest(pixel=pixel),self.assertRaisesRegex(ValueError,'blank|rendered'):
                device.screenshot_metrics(image(lambda x,y:pixel))

    def test_status_and_navigation_icons_cannot_approve_a_black_activity(self):
        png = image(lambda x,y:(255,255,255,255) if y < 40 or y > 440 else (0,0,0,255))
        with self.assertRaisesRegex(ValueError,'blank|rendered'): device.screenshot_metrics(png)

    def test_rendered_content_supports_all_png_filters_rgb_and_rgba(self):
        for mode in range(5):
            for channels in (3,4):
                with self.subTest(mode=mode,channels=channels):
                    result = device.screenshot_metrics(image(content,filter_mode=mode,channels=channels))
                    self.assertEqual((320,480),(result['width'],result['height']))
                    self.assertEqual(220,result['luminanceRange'])
                    self.assertTrue(result['nonBlankPassed'])
                    self.assertFalse(result['humanReviewed'])
                    self.assertGreater(result['nonDominantPixelFraction'],.01)

    def test_corruption_truncation_compression_bombs_and_tiny_images_fail_closed(self):
        png = image(content)
        broken = bytearray(png); broken[40] ^= 1
        for raw in (bytes(broken),png[:-1],b'not png',image(content,width=1,height=1)):
            with self.subTest(length=len(raw)),self.assertRaises(ValueError): device.screenshot_metrics(raw)
        header = struct.pack('>IIBBBBB',320,480,8,6,0,0,0)
        raw = b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',header)+chunk(b'IDAT',zlib.compress(b'\0'*2_000_000))+chunk(b'IEND',b'')
        with self.assertRaisesRegex(ValueError,'expanded budget'): device.screenshot_metrics(raw)

    def test_capture_retries_blank_frame_and_only_labels_valid_content_as_device_png(self):
        frames = iter([image(lambda x,y:(0,0,0,255)),image(content)])
        def adb(*args,**kwargs):
            self.assertEqual(('exec-out','screencap','-p'),args)
            self.assertTrue(kwargs['binary']);return next(frames)
        with tempfile.TemporaryDirectory() as temporary,patch.object(device.time,'sleep'):
            output = Path(temporary)
            result = device.capture_rendered_screenshot(adb,output,attempts=2)
            self.assertTrue(result['nonBlankPassed'])
            self.assertTrue((output/'device.png').is_file())
            self.assertFalse((output/'device-rejected.png').exists())

    def test_only_blank_captures_raise_and_preserve_rejected_diagnostic(self):
        png = image(lambda x,y:(0,0,0,255))
        with tempfile.TemporaryDirectory() as temporary,patch.object(device.time,'sleep'):
            output = Path(temporary)
            with self.assertRaises(ValueError): device.capture_rendered_screenshot(lambda *a,**k:png,output,attempts=2)
            self.assertFalse((output/'device.png').exists())
            self.assertEqual(png,(output/'device-rejected.png').read_bytes())

    def test_owned_component_must_be_resumed_not_only_present_in_backstack(self):
        self.assertTrue(device.owned_activity_foreground('topResumedActivity=ActivityRecord{abc u0 com.wrapperguard.acceptance/.MainActivity t12}'))
        self.assertTrue(device.owned_activity_foreground('mResumedActivity: ActivityRecord{abc u0 com.wrapperguard.acceptance/com.wrapperguard.acceptance.MainActivity t12}'))
        self.assertFalse(device.owned_activity_foreground('Hist #2 ActivityRecord{com.wrapperguard.acceptance/.MainActivity}\nmResumedActivity: ActivityRecord{other/.MainActivity}'))
        self.assertFalse(device.owned_activity_foreground('topResumedActivity=ActivityRecord{com.wrapperguard.acceptance.evil/.MainActivity}'))

    def test_xml_entities_are_decoded_and_required_labels_must_belong_to_owned_package(self):
        status=device.owned_ui_status('<hierarchy><node package="com.wrapperguard.acceptance" text="WrapperGuard&#10;Device acceptance"/><node package="com.wrapperguard.acceptance" text="Ready on 127.0.0.1:8765 &#183; 2 requests"/></hierarchy>')
        self.assertTrue(status['titlePresent'] and status['readyPresent'])
        other=device.owned_ui_status('<hierarchy><node package="other" text="Device acceptance Ready on 127.0.0.1:8765"/></hierarchy>')
        self.assertFalse(other['titlePresent'] or other['readyPresent'])

    def test_ui_capture_retains_tree_and_image_diagnostics_before_rejecting_missing_labels(self):
        png=image(content)
        def adb(*args,**kwargs):
            if args[0]=='exec-out': return png
            if args[1:4]==('dumpsys','activity','activities'):
                return 'topResumedActivity=ActivityRecord{com.wrapperguard.acceptance/.MainActivity}'
            if args[1]=='cat': return '<hierarchy><node package="com.wrapperguard.acceptance" text="Only partially ready"/></hierarchy>'
            return ''
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary)
            with self.assertRaises(RuntimeError): device.capture_owned_ui(adb,output,attempts=1)
            self.assertTrue((output/'device-ui.xml').exists())
            self.assertTrue((output/'device-rejected.png').exists())
            self.assertFalse((output/'device.png').exists())
            import json
            self.assertFalse(json.loads((output/'ui-check.json').read_text())['accepted'])

    def test_ui_capture_settles_without_weakening_title_ready_or_rendered_frame_checks(self):
        png=image(content);calls=[]
        def adb(*args,**kwargs):
            calls.append(args)
            if args[0]=='exec-out': return png
            if args[1:4]==('dumpsys','activity','activities'):
                return 'topResumedActivity=ActivityRecord{com.wrapperguard.acceptance/.MainActivity}'
            if args[1]=='cat':
                if sum(a[1:2]==('cat',) for a in calls)==1: return '<hierarchy/>'
                return '<hierarchy><node package="com.wrapperguard.acceptance" text="Device acceptance"/><node package="com.wrapperguard.acceptance" text="Ready on 127.0.0.1:8765"/></hierarchy>'
            return ''
        with tempfile.TemporaryDirectory() as temporary,patch.object(device.time,'sleep'):
            output=Path(temporary)
            result=device.capture_owned_ui(adb,output,attempts=2)
            self.assertTrue(result['nonBlankPassed'])
            self.assertTrue((output/'device.png').exists())
            self.assertFalse((output/'device-rejected.png').exists())


if __name__ == '__main__': unittest.main()
