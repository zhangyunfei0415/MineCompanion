var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');

function injectInput(method, kind) {
    var code = new InsnList();
    var original = new LabelNode();
    if (kind === 'keys') {
        code.add(new VarInsnNode(Opcodes.LLOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, 'dev/mibao/companion/VirtualInput', 'keyState', '(JI)I', false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFLT, original));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(original);
        code.add(new InsnNode(Opcodes.POP));
    } else {
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, 'dev/mibao/companion/VirtualInput', 'focused', '()Z', false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, original));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(original);
    }
    method.instructions.insert(code);
    method.maxStack += 5;
    return method;
}
function injectGate(method, kind) {
    var code = new InsnList(); var original = new LabelNode();
    if (kind === 'mouse') {
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, 'dev/mibao/companion/WatchMode', 'blocksWorldMouse', '()Z', false));
    } else {
        code.add(new VarInsnNode(Opcodes.LLOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        if(kind === 'key'){code.add(new VarInsnNode(Opcodes.ILOAD, 5));code.add(new VarInsnNode(Opcodes.ILOAD, 6));}
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, 'dev/mibao/companion/WatchMode', kind==='key'?'key':'character', kind==='key'?'(JIIII)Z':'(JII)Z', false));
    }
    code.add(new JumpInsnNode(Opcodes.IFEQ,original));code.add(new InsnNode(Opcodes.RETURN));code.add(original);method.instructions.insert(code);method.maxStack+=8;return method;
}
function injectMenuReceipt(method) {
    var nodes = method.instructions.toArray();
    for (var i=0;i<nodes.length;i++) {var node=nodes[i];
        if (node.getOpcode() === Opcodes.RETURN) {
            var code = new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, 'dev/mibao/companion/ModMenuReceipts', 'received', '(Ljava/lang/Object;)V', false));
            method.instructions.insertBefore(node, code);
        }
    }
    method.maxStack += 1;return method;
}
function injectPointer(method, kind) {
    var nodes = method.instructions.toArray();
    for (var i=0;i<nodes.length;i++) {
        var node=nodes[i];
        if ((kind==='x'||kind==='y') && node.getOpcode()===Opcodes.DRETURN) {
            method.instructions.insertBefore(node,new MethodInsnNode(Opcodes.INVOKESTATIC,'dev/mibao/companion/AgentPointer',kind==='x'?'mouseX':'mouseY','(D)D',false));
        } else if (typeof kind==='number' && node.getOpcode()===Opcodes.IRETURN) {
            var code=new InsnList();code.add(new InsnNode(Opcodes.ICONST_0+kind));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,'dev/mibao/companion/AgentPointer','mouseButton','(ZI)Z',false));
            method.instructions.insertBefore(node,code);
        }
    }
    method.maxStack+=2;return method;
}
function initializeCoreMod() {
    return {
        'companion_pointer_x':{target:{type:'METHOD',class:'net.minecraft.client.MouseHandler',methodName:ASMAPI.mapMethod('m_91589_'),methodDesc:'()D'},transformer:function(m){return injectPointer(m,'x');}},
        'companion_pointer_y':{target:{type:'METHOD',class:'net.minecraft.client.MouseHandler',methodName:ASMAPI.mapMethod('m_91594_'),methodDesc:'()D'},transformer:function(m){return injectPointer(m,'y');}},
        'companion_pointer_left':{target:{type:'METHOD',class:'net.minecraft.client.MouseHandler',methodName:ASMAPI.mapMethod('m_91560_'),methodDesc:'()Z'},transformer:function(m){return injectPointer(m,0);}},
        'companion_pointer_right':{target:{type:'METHOD',class:'net.minecraft.client.MouseHandler',methodName:ASMAPI.mapMethod('m_91584_'),methodDesc:'()Z'},transformer:function(m){return injectPointer(m,1);}},
        'companion_pointer_middle':{target:{type:'METHOD',class:'net.minecraft.client.MouseHandler',methodName:ASMAPI.mapMethod('m_168090_'),methodDesc:'()Z'},transformer:function(m){return injectPointer(m,2);}},
        'companion_ms_content': {target:{type:'METHOD',class:'com.robertx22.mine_and_slash.vanilla_mc.packets.backpack.SetBackpackContentPacket',methodName:'onReceived',methodDesc:'(Lcom/robertx22/library_of_exile/packets/ExilePacketContext;)V'},transformer:injectMenuReceipt},
        'companion_ms_slot': {target:{type:'METHOD',class:'com.robertx22.mine_and_slash.vanilla_mc.packets.backpack.SetBackpackSlotPacket',methodName:'onReceived',methodDesc:'(Lcom/robertx22/library_of_exile/packets/ExilePacketContext;)V'},transformer:injectMenuReceipt},
        'companion_sophisticated_full': {target:{type:'METHOD',class:'net.p3pp3rf1y.sophisticatedcore.network.SyncContainerStacksMessage',methodName:'handleMessage',methodDesc:'(Lnet/p3pp3rf1y/sophisticatedcore/network/SyncContainerStacksMessage;)V'},transformer:injectMenuReceipt},
        'companion_sophisticated_slot': {target:{type:'METHOD',class:'net.p3pp3rf1y.sophisticatedcore.network.SyncSlotStackMessage',methodName:'handleMessage',methodDesc:'(Lnet/p3pp3rf1y/sophisticatedcore/network/SyncSlotStackMessage;)V'},transformer:injectMenuReceipt},
        'companion_physical_keys': {target:{type:'METHOD',class:'net.minecraft.client.KeyboardHandler',methodName:ASMAPI.mapMethod('m_90893_'),methodDesc:'(JIIII)V'},transformer:function(method){return injectGate(method,'key');}},
        'companion_local_text': {target:{type:'METHOD',class:'net.minecraft.client.KeyboardHandler',methodName:ASMAPI.mapMethod('m_90889_'),methodDesc:'(JII)V'},transformer:function(method){return injectGate(method,'char');}},
        'companion_watch_mouse': {target:{type:'METHOD',class:'net.minecraft.client.MouseHandler',methodName:ASMAPI.mapMethod('m_91523_'),methodDesc:'()V'},transformer:function(method){return injectGate(method,'mouse');}},
        'companion_game_keys': {
            target: {type:'METHOD', class:'com.mojang.blaze3d.platform.InputConstants', methodName:ASMAPI.mapMethod('m_84830_'), methodDesc:'(JI)Z'},
            transformer: function(method) { return injectInput(method, 'keys'); }
        },
        'companion_background_lease': {
            target: {type:'METHOD', class:'net.minecraft.client.Minecraft', methodName:ASMAPI.mapMethod('m_91302_'), methodDesc:'()Z'},
            transformer: function(method) { return injectInput(method, 'focus'); }
        }
    };
}
