import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cs2_skins  # noqa: E402


class ShineParamsTest(unittest.TestCase):
    def test_anodized_is_metallic_and_pearlescence_normalised(self):
        shine = cs2_skins.shine_params({'g_flPaintRoughness': 0.2, 'g_vPaintMetalness': [0, 0.3, 0, 0],
                                        'g_flPearlescentScale': 3.0}, 5)
        self.assertEqual(shine, {'shine_schema': 1, 'roughness': 0.2, 'metalness': 0.8, 'pearlescence': 0.5, 'style': 5})

    def test_defaults_and_bad_values(self):
        shine = cs2_skins.shine_params({'g_flPaintRoughness': 'x', 'g_vPaintMetalness': 'y'}, 2)
        self.assertEqual((shine['roughness'], shine['metalness'], shine['pearlescence']), (0.04, 0.0, 0.0))
        self.assertEqual(cs2_skins.shine_params({}, 7)['roughness'], 0.6)

    def test_refresh_writes_only_shine_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'manifest.json').write_text(json.dumps({'weapons': {'ak47': {}, 'knife_karambit': {'kind': 'knife'},
                                                                       'hegrenade': {}, 'c4': {'kind': 'equipment'}}}))
            params = root / 'skins' / 'ak47' / '801' / 'params.json'
            params.parent.mkdir(parents=True)
            params.write_text(json.dumps({'effective_style': 7, 'params': {'g_flPaintRoughness': 0.5}}))
            before = params.read_bytes()
            self.assertEqual(cs2_skins.refresh_shine(root), {'pairs': 1, 'defaults': 2, 'masks': 0})
            self.assertEqual(params.read_bytes(), before)
            self.assertEqual(json.loads((root / 'skins/knife_karambit/_default/shine.json').read_text())['metalness'], 0.9)
            self.assertFalse((root / 'skins/hegrenade').exists())

    def test_mask_per_mesh_set_from_orm_and_cavity(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'manifest.json').write_text(json.dumps({'weapons': {'ak47': {'kind': 'gun'}}}))
            models = root / 'models' / 'ak47'
            models.mkdir(parents=True)
            Image.new('RGB', (1024, 512), (9, 200, 30)).save(models / 'orm.png')
            Image.new('RGB', (64, 64), (1, 2, 3)).save(models / 'color.png')
            (models / 'ak47.gltf').write_text(json.dumps({
                'images': [{'uri': 'color.png'}, {'uri': 'orm.png'}],
                'textures': [{'source': 0}, {'source': 1}],
                'accessors': [{'count': 10}],
                'materials': [{'pbrMetallicRoughness': {'baseColorTexture': {'index': 0},
                                                        'metallicRoughnessTexture': {'index': 1}}}],
                'meshes': [{'name': 'x.vmdl_c.body_legacy', 'primitives': [{'material': 0, 'attributes': {'POSITION': 0}}]}]}))
            kit = root / 'skins' / 'ak47' / '801'
            kit.mkdir(parents=True)
            Image.new('RGB', (256, 256), (255, 0, 0)).save(kit / 'cavity.png')
            (kit / 'params.json').write_text(json.dumps({'legacy_model': True, 'effective_style': 7,
                                                         'inputs': {'cavity': 'cavity.png'}, 'params': {}}))
            self.assertEqual(cs2_skins.refresh_shine(root), {'pairs': 1, 'defaults': 1, 'masks': 1})
            with Image.open(root / 'skins/ak47/_default/shine_mask_body_legacy.png') as mask:
                self.assertEqual(mask.size, (512, 256))
                self.assertEqual(mask.getpixel((10, 10)), (200, 30, 255))


class CacheGuardTest(unittest.TestCase):
    def test_rejects_cache_inside_repository(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp).resolve()
            (repo / 'cache').mkdir()
            for cache in (repo, repo / 'cache', repo / 'cache' / '..' / 'cache'):
                with self.assertRaisesRegex(ValueError, 'outside the repository'):
                    cs2_skins.checked_cache(cache, repo=repo)

    def test_rejects_symlinks_in_cache(self):
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as elsewhere:
            cache = Path(tmp) / 'cache'
            (cache / 'skins' / 'ak47').mkdir(parents=True)
            (cache / 'skins' / 'ak47' / '_default').symlink_to(elsewhere, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'symlinks'):
                cs2_skins.checked_cache(cache, repo=Path(tmp) / 'repo')

    def test_accepts_resolved_external_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / 'cache'
            with self.assertRaisesRegex(ValueError, 'existing directory'):
                cs2_skins.checked_cache(cache, repo=Path(tmp) / 'repo', create=False)
            self.assertEqual(cs2_skins.checked_cache(cache, repo=Path(tmp) / 'repo'), cache.resolve())
            self.assertTrue(cache.is_dir())

    def test_shine_command_refuses_before_writing(self):
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as elsewhere:
            cache = Path(tmp)
            (cache / 'manifest.json').write_text(json.dumps({'weapons': {'ak47': {'kind': 'gun'}}}))
            (cache / 'skins' / 'ak47').mkdir(parents=True)
            (cache / 'skins' / 'ak47' / '_default').symlink_to(elsewhere, target_is_directory=True)
            with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
                cs2_skins.main(['--shine', '--cache', str(cache)])
            self.assertEqual(list(Path(elsewhere).iterdir()), [])
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            cs2_skins.main(['--shine', '--cache', str(cs2_skins.REPO / 'tools')])


class PublishTest(unittest.TestCase):
    def test_publishes_without_fchmod(self):
        import os
        saved = getattr(os, 'fchmod', None)
        if saved is not None:
            del os.fchmod
        try:
            with tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp) / 'shine.json'
                cs2_skins.save(target, {'a': 1})
                self.assertEqual(json.loads(target.read_text()), {'a': 1})
                self.assertEqual([p.name for p in Path(tmp).iterdir()], ['shine.json'])
        finally:
            if saved is not None:
                os.fchmod = saved

    @unittest.skipUnless(__import__('os').name == 'posix', 'POSIX permissions only')
    def test_posix_output_is_world_readable(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'shine.json'
            cs2_skins.save(target, {'a': 1})
            self.assertEqual(target.stat().st_mode & 0o777, 0o644)

    def test_does_not_follow_predictable_temp_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            victim = root / 'victim.json'
            victim.write_text('keep')
            target = root / 'out' / 'shine.json'
            target.parent.mkdir()
            (target.parent / 'shine.json.tmp').symlink_to(victim)
            cs2_skins.save(target, {'a': 1})
            self.assertEqual(victim.read_text(), 'keep')
            self.assertEqual(json.loads(target.read_text()), {'a': 1})
            self.assertEqual(sorted(p.name for p in target.parent.iterdir()), ['shine.json', 'shine.json.tmp'])

    def test_failure_removes_temp_and_keeps_previous(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'shine.json'
            target.write_text('old')

            def fail(handle):
                handle.write(b'partial')
                raise RuntimeError('boom')
            with self.assertRaises(RuntimeError):
                cs2_skins.publish(target, fail)
            self.assertEqual(target.read_text(), 'old')
            self.assertEqual([p.name for p in Path(tmp).iterdir()], ['shine.json'])

    def test_temp_files_are_unique(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'shine.json'
            temps = []

            def inner(handle):
                temps.extend(p.name for p in Path(tmp).iterdir() if p.name.endswith('.tmp'))
                handle.write(b'inner')

            def outer(handle):
                cs2_skins.publish(target, inner)  # a concurrent writer of the same destination
                handle.write(b'outer')
            cs2_skins.publish(target, outer)
            self.assertEqual(len(set(temps)), 2)
            self.assertNotIn('shine.json.tmp', temps)
            self.assertEqual(target.read_bytes(), b'outer')
            self.assertEqual([p.name for p in Path(tmp).iterdir()], ['shine.json'])

    def test_mask_png_publication_leaves_no_temp(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            Image.new('RGB', (8, 8), (1, 2, 3)).save(root / 'orm.png')
            out = root / 'masks' / 'shine_mask_body_hd.png'
            self.assertEqual(cs2_skins.shine_mask(root / 'orm.png', None, out), (8, 8))
            self.assertEqual(sorted(p.name for p in out.parent.iterdir()), ['shine_mask_body_hd.png'])
            with Image.open(out) as mask:
                self.assertEqual(mask.getpixel((0, 0)), (2, 3, 0))


if __name__ == '__main__':
    unittest.main()
