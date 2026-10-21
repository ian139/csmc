"""First-person arms selection and skeleton reports for export-cs2-assets --arms (Python 3 stdlib)."""
import re

# Default CT/T agents carry the first-person meshes CS2 renders: bare arms with the default
# gloves, plus the agent's sleeves. The two items_game default gloves (ct_gloves / t_gloves)
# are exported as swappable viewmodel glove+wrist meshes.
PARTS = {
    'ctm_sas': {'source': 'agents/models/ctm_sas/ctm_sas.vmdl_c', 'team': 'ct',
                'meshes': {'firstperson_default_gloves_arms': 'arms_gloves', 'firstperson_sleeves': 'sleeves'}},
    'tm_phoenix': {'source': 'agents/models/tm_phoenix/tm_phoenix.vmdl_c', 'team': 't',
                   'meshes': {'firstperson_default_gloves_arms': 'arms_gloves', 'firstperson_sleeves': 'sleeves'}},
    'glove_hardknuckle': {'source': 'agents/models/shared/arms/glove_hardknuckle/glove_hardknuckle.vmdl_c', 'team': 'ct',
                          'meshes': {'viewmodel': 'arms_gloves'}},
    'glove_fingerless': {'source': 'agents/models/shared/arms/glove_fingerless/glove_fingerless.vmdl_c', 'team': 't',
                         'meshes': {'viewmodel': 'arms_gloves'}},
}
DEFAULTS = {'ct': {'ctm_sas': ['firstperson_default_gloves_arms', 'firstperson_sleeves']},
            't': {'tm_phoenix': ['firstperson_default_gloves_arms', 'firstperson_sleeves']}}
# Any clip node name set works; this reference proves the overlap written into the record.
CLIP_REFERENCE = 'models/knife/clips'
RETARGET_NOTE = ('Meshes are skinned to the character skeleton in its T-pose bind; matched joints share bone axes and '
                 'lengths with the viewmodel clip skeleton but not its hierarchy. Retarget in model space: '
                 'skin(joint) = clipGlobal(joint) * inverseBind(joint). An unmatched joint follows its anchor: '
                 'clipGlobal(anchor) * inverse(bindGlobal(anchor)) * bindGlobal(joint).')


def mesh_suffix(node_name):
    """`agents/models/x/x.vmdl_c.firstperson_sleeves` -> `firstperson_sleeves`."""
    return node_name.rpartition('.vmdl_c.')[2] if '.vmdl_c.' in node_name else node_name


def missing_chunks(message):
    """Sorted absent VPK chunk names when every `; `-joined CLI error is a missing-chunk error, else []."""
    errors = [part for part in message.split('; ') if part.strip()]
    found = [re.findall(r"Could not find file '[^']*?(pak\d+_\d{3}\.vpk)'", error) for error in errors]
    return sorted({name for names in found for name in names}) if errors and all(found) else []


def sidecars(model):
    """External buffer/image URIs referenced by a glTF document, in order."""
    return list(dict.fromkeys(entry['uri'] for kind in ('buffers', 'images')
                              for entry in model.get(kind, []) if 'uri' in entry))


def joint_anchors(names, parents, matched, root):
    """Unmatched joint -> matched joint whose clip transform it follows.

    Nearest matched ancestor below the skeleton root; otherwise (e.g. clavicles hanging off the
    unanimated spine) the nearest matched descendant; otherwise the root.
    """
    children = {}
    for child, parent in parents.items():
        children.setdefault(parent, []).append(child)
    anchors = {}
    for name in names:
        if name in matched:
            continue
        anchor, node = None, parents.get(name)
        while node is not None:
            if node in matched and node != root:
                anchor = node
                break
            node = parents.get(node)
        if anchor is None:
            queue = list(children.get(name, []))
            while queue and anchor is None:
                node = queue.pop(0)
                if node in matched:
                    anchor = node
                else:
                    queue.extend(children.get(node, []))
        anchors[name] = anchor or root
    return anchors


def skin_report(model, clip_names):
    """Per rendered mesh node: skeleton root, joints and clip-name overlap."""
    nodes = model.get('nodes', [])
    names = [node.get('name', '') for node in nodes]
    parents = {names[child]: names[index] for index, node in enumerate(nodes) for child in node.get('children', [])}
    meshes = {}
    for node in nodes:
        if 'mesh' not in node or 'skin' not in node:
            continue
        joints = [names[index] for index in model['skins'][node['skin']]['joints']]
        matched = [name for name in joints if name in clip_names]
        root = next((name for name in joints if parents.get(name) not in joints), joints[0] if joints else None)
        primitives = model['meshes'][node['mesh']].get('primitives', [])
        meshes[mesh_suffix(node.get('name', ''))] = {
            'node': node.get('name', ''), 'skeleton_root': root, 'joints': joints,
            'matched_joints': len(matched), 'unmatched_joints': sorted(set(joints) - set(matched)),
            'joint_anchors': joint_anchors(joints, parents, set(matched), root),
            'materials': [model['materials'][p['material']].get('name', '') for p in primitives if 'material' in p]}
    return meshes
