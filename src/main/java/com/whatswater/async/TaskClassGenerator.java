package com.whatswater.async;


import com.whatswater.async.GenerateClassData.ClassType;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;

public class TaskClassGenerator implements MethodInsnVisitor {
    public static final String JAVA_FILE_SUFFIX = ".java";
    public static final String SETTER_PREFIX = "set_";
    public static final String LOCAL_VARIABLE_NAME_PREFIX = "local_";

    public static final String TASK_CLASS_NAME = "Task";
    public static final String TASK_INTERFACE_NAME = "com/whatswater/async/Task";

    public static final String OBJECT_CLASS_PATH = "java/lang/Object";
    public static final String OBJECT_CLASS_DESC = TransformerHelper.classPathDesc(OBJECT_CLASS_PATH);

    public static final String FUTURE_PROPERTY_NAME = "_future";
    public static final String FUTURE_INTERFACE_NAME = "io/vertx/core/Future";
    public static final String FUTURE_IMPL_CLASS_PATH = "com/whatswater/async/future/TaskFutureImpl";
    public static final String FUTURE_IMPL_CLASS_DESC = TransformerHelper.classPathDesc(FUTURE_IMPL_CLASS_PATH);

    public static final String HANDLER_PROPERTY_NAME = "_handler";
    public static final String HANDLER_CLASS_PATH = "com/whatswater/async/handler/AwaitTaskHandler";
    public static final String HANDLER_CLASS_DESC = TransformerHelper.classPathDesc(HANDLER_CLASS_PATH);

    public static final String STACK_HOLDER_PROPERTY_NAME = "_stackHolder";

    public static final String COMPLETE_METHOD_NAME = "completeFuture";
    public static final String COMPLETE_METHOD_DESC = "(" + OBJECT_CLASS_DESC + FUTURE_IMPL_CLASS_DESC + ")V";

    public static final String METHOD_NAME_MOVE_TO_NEXT = "moveToNext";
    public static final String METHOD_DESC_MOVE_TO_NEXT = "(I)V";

    private final ClassNode classNode;
    private final MethodNode methodNode;
    private final String suffix;
    private final String taskClassName;
    private final String taskClassDesc;
    private final Frame<BasicValue>[] frames;
    private final ClassWriter taskClassWriter;

    private MethodVisitor methodVisitor;
    private Map<Integer, List<LocalSetterInfo>> propertyNameAndDescList;
    private Label[] switchLabels;
    private int maxStackSize = 0;
    private int labelIndex = 0;
    private int insnIndex = 0;
    private List<GenerateClassData> generateClassDataList = new ArrayList<>();

    public TaskClassGenerator(ClassNode classNode, MethodNode methodNode, String suffix) throws AnalyzerException {
        this.classNode = classNode;
        this.methodNode = methodNode;
        this.suffix = suffix;

        this.taskClassName = classNode.name + '$' + TASK_CLASS_NAME + suffix;
        this.taskClassDesc = TransformerHelper.classPathDesc(this.taskClassName);
        this.frames = TransformerHelper.computeFrame(classNode.name, methodNode);
        this.taskClassWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    }

    public void generate() throws AnalyzerException {
        taskClassWriter.visit(V1_8, ACC_PUBLIC | ACC_SUPER, taskClassName, null, OBJECT_CLASS_PATH, new String[] { TASK_INTERFACE_NAME });
        taskClassWriter.visitSource(TransformerHelper.getSimpleNameByClassName(classNode.name) + JAVA_FILE_SUFFIX, null);

        propertyNameAndDescList = copyLocalVariablesToProperties();
        addFutureProperty();
        addHandlerProperty();
        addStackHolderProperty();
        addCompleteMethod();
        addEmptyConstructor();

        methodVisitor = taskClassWriter.visitMethod(ACC_PUBLIC, METHOD_NAME_MOVE_TO_NEXT, METHOD_DESC_MOVE_TO_NEXT, null, null);
        methodVisitor.visitCode();

        copyTryCatchNode();
        addOuterTryCatchNode();
        createStateSwitch();
        transformInsn();
        visitDefault();

        // 转换过程中未引入任何本地变量
        methodVisitor.visitMaxs(maxStackSize, 2);
        methodVisitor.visitEnd();
    }

    public void copyTryCatchNode() {
        for (TryCatchBlockNode tryCatchBlockNode: methodNode.tryCatchBlocks) {
            methodVisitor.visitTryCatchBlock(
                tryCatchBlockNode.start.getLabel(),
                tryCatchBlockNode.end.getLabel(),
                tryCatchBlockNode.handler.getLabel(),
                tryCatchBlockNode.type
            );
        }
    }

    public void addOuterTryCatchNode() {

    }

    public void createStateSwitch() {
        int awaitCount = TransformerHelper.awaitInvokeCount(methodNode);

        Label defaultLabel = new Label();
        Label[] switchLabels = new Label[awaitCount + 1];
        for (int i = 0; i < awaitCount + 1; i++) {
            switchLabels[i] = new Label();
        }
        methodVisitor.visitVarInsn(ILOAD, 1);
        methodVisitor.visitTableSwitchInsn(0, awaitCount, defaultLabel, switchLabels);
        methodVisitor.visitLabel(switchLabels[labelIndex]);

        this.switchLabels = switchLabels;
    }

    public void transformInsn() {
        for (int i = 0; i < methodNode.instructions.size(); i++) {
            AbstractInsnNode abstractInsnNode = methodNode.instructions.get(i);
            insnIndex = i;
            if (abstractInsnNode instanceof MethodInsnNode) {
                this.visitMethodInsnNode((MethodInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof FieldInsnNode) {
                this.visitFieldInsnNode((FieldInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof TableSwitchInsnNode) {
                this.visitTableSwitchInsnNode((TableSwitchInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof LineNumberNode) {
                this.visitLineNumberNode((LineNumberNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof IincInsnNode) {
                this.visitIincInsnNode((IincInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof IntInsnNode) {
                this.visitIntInsnNode((IntInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof LabelNode) {
                this.visitLabelNode((LabelNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof MultiANewArrayInsnNode) {
                this.visitMultiANewArrayInsnNode((MultiANewArrayInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof LdcInsnNode) {
                this.visitLdcInsnNode((LdcInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof TypeInsnNode) {
                this.visitTypeInsnNode((TypeInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof VarInsnNode) {
                this.visitVarInsn((VarInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof InvokeDynamicInsnNode) {
                this.visitInvokeDynamicInsnNode((InvokeDynamicInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof FrameNode) {
                this.visitFrameNode((FrameNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof JumpInsnNode) {
                this.visitJumpInsnNode((JumpInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof LookupSwitchInsnNode) {
                this.visitLookupSwitchInsnNode((LookupSwitchInsnNode) abstractInsnNode, frames[i]);
            } else if (abstractInsnNode instanceof InsnNode) {
                this.visitInsnNode((InsnNode) abstractInsnNode, frames[i]);
            }
        }
    }

    public void visitDefault() {
//        // default label至少需要两个栈空间
//        if (maxStackSize < 2) {
//            maxStackSize = 2;
//        }
//        // default label
//        methodVisitor.visitLabel(defaultLabel);
//        methodVisitor.visitVarInsn(ALOAD, 0);
//        methodVisitor.visitFieldInsn(GETFIELD, taskClassName, FUTURE_PROPERTY_NAME, FUTURE_IMPL_CLASS_DESC);
//        methodVisitor.visitVarInsn(ALOAD, 0);
//        methodVisitor.visitFieldInsn(GETFIELD, taskClassName, HANDLER_PROPERTY_NAME, HANDLER_CLASS_DESC);
//        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, HANDLER_CLASS_PATH, "getThrowable", "()Ljava/lang/Throwable;", false);
//        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, FUTURE_IMPL_CLASS_PATH, "tryFail", "(Ljava/lang/Throwable;)Z", false);
//        methodVisitor.visitInsn(POP);
//        methodVisitor.visitInsn(RETURN);
    }

    /**
     * 将本地变量表复制到Task类，并生成setter方法
     * @return 本地变量index => name、desc、setter、setter desc
     */
    public Map<Integer, List<LocalSetterInfo>> copyLocalVariablesToProperties() {
        Map<Integer, List<LocalSetterInfo>> propertyNames = new TreeMap<>();

        List<LocalVariableNode> tmp = new ArrayList<>(methodNode.localVariables.size());
        tmp.addAll(methodNode.localVariables);
        tmp.sort(Comparator.comparingInt(n -> n.index));

        for (int i = 0; i < tmp.size(); i++) {
            LocalVariableNode localVariable = tmp.get(i);
            String name = LOCAL_VARIABLE_NAME_PREFIX + i;
            String desc = localVariable.desc;

            FieldVisitor fieldVisitor = taskClassWriter.visitField(
                ACC_PUBLIC,
                name,
                desc,
                null,  // localVariable.signature,
                null
            );
            fieldVisitor.visitEnd();

            String setterName = SETTER_PREFIX + name;
            String setterDesc = "(" + desc + taskClassDesc + ")V";
            LocalSetterInfo setterInfo = new LocalSetterInfo(name, desc, setterName, setterDesc);
            List<LocalSetterInfo> list = propertyNames.computeIfAbsent(localVariable.index, k -> new ArrayList<>());
            list.add(setterInfo);
            MethodVisitor methodVisitor = taskClassWriter.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                setterName,
                setterDesc,
                null,
                null
            );

            Type type = Type.getType(desc);
            methodVisitor.visitCode();
            methodVisitor.visitVarInsn(ALOAD, type.getSize());
            methodVisitor.visitVarInsn(type.getOpcode(ILOAD), 0);
            methodVisitor.visitFieldInsn(PUTFIELD, taskClassName, name, desc);
            methodVisitor.visitInsn(RETURN);
            methodVisitor.visitMaxs(type.getSize() + 1, type.getSize() + 1);
            methodVisitor.visitEnd();
        }
        return propertyNames;
    }

    /**
     * 添加future属性，作为原async方法的返回值
     */
    public void addFutureProperty() {
        FieldVisitor fieldVisitor = taskClassWriter.visitField(
            ACC_PUBLIC,
            FUTURE_PROPERTY_NAME,
            FUTURE_IMPL_CLASS_DESC,
            null,  // 泛型签名,
            null
        );
        fieldVisitor.visitEnd();
    }

    /**
     * 异步调用的handler属性
     */
    public void addHandlerProperty() {
        FieldVisitor fieldVisitor = taskClassWriter.visitField(
            ACC_PUBLIC,
            HANDLER_PROPERTY_NAME,
            HANDLER_CLASS_DESC,
            null,  // 泛型签名,
            null
        );
        fieldVisitor.visitEnd();
    }

    /**
     * 栈属性，用于保存栈对象
     */
    public void addStackHolderProperty() {
        FieldVisitor fieldVisitor = taskClassWriter.visitField(
            ACC_PUBLIC,
            STACK_HOLDER_PROPERTY_NAME,
            OBJECT_CLASS_DESC,
            null,  // 泛型签名,
            null
        );
        fieldVisitor.visitEnd();
    }

    public void addCompleteMethod() {
        MethodVisitor methodVisitor = taskClassWriter.visitMethod(
            ACC_PRIVATE | ACC_STATIC,
            COMPLETE_METHOD_NAME,
            COMPLETE_METHOD_DESC,
            null,
            null
        );
        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(
            INVOKEVIRTUAL,
            FUTURE_IMPL_CLASS_PATH,
            "tryComplete",
            "(Ljava/lang/Object;)Z",
            false
        );
        methodVisitor.visitInsn(POP);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(2, 2);
        methodVisitor.visitEnd();
    }

    public void addEmptyConstructor() {
        MethodVisitor methodVisitor = taskClassWriter.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitTypeInsn(NEW, "com/whatswater/async/future/TaskFutureImpl");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "com/whatswater/async/future/TaskFutureImpl", "<init>", "()V", false);
        methodVisitor.visitFieldInsn(PUTFIELD, taskClassName, FUTURE_PROPERTY_NAME, "Lcom/whatswater/async/future/TaskFutureImpl;");

        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(3, 1);
        methodVisitor.visitEnd();
    }

    @Override
    public void visitMethodInsnNode(MethodInsnNode methodInsnNode, Frame<BasicValue> frame) {
        if (TransformerHelper.isAwaitCall(methodInsnNode)) {
            if (frame != null) {
                int newMax = Math.max(frame.getStackSize() + 5, frame.getMaxStackSize());
                maxStackSize = Math.max(maxStackSize, newMax);
            }

            // 原先的栈顶，为一个Future对象
            // 创建AwaitTaskHandler对象，并传入this和labelIndex，将新创建的AwaitTaskHandler设置为handlerPropertyName属性
            methodVisitor.visitVarInsn(ALOAD, 0);
            methodVisitor.visitTypeInsn(NEW, "com/whatswater/async/handler/AwaitTaskHandler");
            methodVisitor.visitInsn(DUP);
            methodVisitor.visitVarInsn(ALOAD, 0);
            labelIndex = labelIndex + 1;
            switch (labelIndex) {
                case 1:
                    methodVisitor.visitInsn(ICONST_1);
                    break;
                case 2:
                    methodVisitor.visitInsn(ICONST_2);
                    break;
                case 3:
                    methodVisitor.visitInsn(ICONST_3);
                    break;
                case 4:
                    methodVisitor.visitInsn(ICONST_4);
                    break;
                case 5:
                    methodVisitor.visitInsn(ICONST_5);
                    break;
                default:
                    if (labelIndex <= Byte.MAX_VALUE) {
                        methodVisitor.visitIntInsn(BIPUSH, labelIndex);
                    } else if (labelIndex <= Short.MAX_VALUE) {
                        methodVisitor.visitIntInsn(SIPUSH, labelIndex);
                    } else {
                        methodVisitor.visitLdcInsn(labelIndex);
                    }
            }
            methodVisitor.visitMethodInsn(INVOKESPECIAL, HANDLER_CLASS_PATH, "<init>", "(Lcom/whatswater/async/Task;I)V", false);
            methodVisitor.visitFieldInsn(PUTFIELD, taskClassName, HANDLER_PROPERTY_NAME, HANDLER_CLASS_DESC);

            methodVisitor.visitVarInsn(ALOAD, 0);
            methodVisitor.visitFieldInsn(GETFIELD,  taskClassName, HANDLER_PROPERTY_NAME, HANDLER_CLASS_DESC);
            // 栈顶的对象在此使用，调用onComplete后，栈顶存在一个新的Future对象
            methodVisitor.visitMethodInsn(INVOKEINTERFACE, FUTURE_INTERFACE_NAME, "onComplete", "(Lio/vertx/core/Handler;)Lio/vertx/core/Future;", true);
            // 丢失掉新的Future对象
            methodVisitor.visitInsn(POP);

            if (frame != null && frame.getStackSize() > 1) {
                String stackClassName = classNode.name +  "$StackHolder" + suffix + labelIndex;
                ClassWriter stackClassWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                Map<Integer, String[]> stackHolderNameList = TransformerHelper.generateStackMapHolder(classNode, stackClassWriter, stackClassName, frame);
                generateClassDataList.add(new GenerateClassData(stackClassWriter, stackClassName, ClassType.STACK_HOLDER, null));

                methodVisitor.visitVarInsn(ALOAD, 0);
                methodVisitor.visitTypeInsn(NEW, stackClassName);
                methodVisitor.visitInsn(DUP);
                methodVisitor.visitMethodInsn(INVOKESPECIAL, stackClassName, "<init>", "()V", false);
                methodVisitor.visitFieldInsn(PUTFIELD, taskClassName, STACK_HOLDER_PROPERTY_NAME, OBJECT_CLASS_DESC);

                // 保存栈数据
                for (int index = frame.getStackSize() - 2; index >= 0; index--) {
                    String[] names = stackHolderNameList.get(index);
                    if (names == null) {
                        continue;
                    }

                    String setterName = names[2];
                    String setterDesc = names[3];
                    methodVisitor.visitVarInsn(ALOAD, 0);
                    methodVisitor.visitFieldInsn(GETFIELD, taskClassName, STACK_HOLDER_PROPERTY_NAME, OBJECT_CLASS_DESC);
                    methodVisitor.visitTypeInsn(CHECKCAST, stackClassName);
                    methodVisitor.visitMethodInsn(INVOKESTATIC, stackClassName, setterName, setterDesc, false);
                }
                methodVisitor.visitInsn(RETURN);
                methodVisitor.visitLabel(switchLabels[labelIndex]);

                // 恢复栈数据
                for (int index = 0; index < frame.getStackSize() - 1; index++) {
                    String[] names = stackHolderNameList.get(index);
                    if (names == null) {
                        continue;
                    }

                    String propertyName = names[0];
                    String propertyDesc = names[1];
                    methodVisitor.visitVarInsn(ALOAD, 0);
                    methodVisitor.visitFieldInsn(GETFIELD, taskClassName, STACK_HOLDER_PROPERTY_NAME, OBJECT_CLASS_DESC);
                    methodVisitor.visitTypeInsn(CHECKCAST, stackClassName);
                    methodVisitor.visitFieldInsn(GETFIELD, stackClassName, propertyName, propertyDesc);
                }
            } else {
                methodVisitor.visitInsn(RETURN);
                methodVisitor.visitLabel(switchLabels[labelIndex]);
            }
            // 获取异步任务的执行结果
            methodVisitor.visitVarInsn(ALOAD, 0);
            methodVisitor.visitFieldInsn(GETFIELD, taskClassName, HANDLER_PROPERTY_NAME, HANDLER_CLASS_DESC);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, HANDLER_CLASS_PATH, "getResult", "()Ljava/lang/Object;", false);
        } else if (TransformerHelper.isAsyncCall(methodInsnNode)) {
            methodVisitor.visitInsn(NOP);
        } else {
            methodVisitor.visitMethodInsn(methodInsnNode.getOpcode(), methodInsnNode.owner, methodInsnNode.name, methodInsnNode.desc, methodInsnNode.itf);
        }
    }

    @Override
    public void visitFieldInsnNode(FieldInsnNode fieldInsnNode, Frame<BasicValue> frame) {
        methodVisitor.visitFieldInsn(fieldInsnNode.getOpcode(), fieldInsnNode.owner, fieldInsnNode.name, fieldInsnNode.desc);
    }

    @Override
    public void visitTableSwitchInsnNode(TableSwitchInsnNode tableSwitchInsnNode, Frame<BasicValue> frame) {
        Label[] labels = new Label[tableSwitchInsnNode.labels.size()];
        for (int j = 0; j < tableSwitchInsnNode.labels.size(); j++) {
            labels[j] = tableSwitchInsnNode.labels.get(j).getLabel();
        }
        methodVisitor.visitTableSwitchInsn(tableSwitchInsnNode.min, tableSwitchInsnNode.max, tableSwitchInsnNode.dflt.getLabel(), labels);
    }

    @Override
    public void visitLineNumberNode(LineNumberNode lineNumberNode, Frame<BasicValue> frame) {
        methodVisitor.visitLineNumber(lineNumberNode.line, lineNumberNode.start.getLabel());
    }

    @Override
    public void visitIincInsnNode(IincInsnNode iincInsnNode, Frame<BasicValue> frame) {
        int index = iincInsnNode.var;
        List<LocalSetterInfo> localSetterInfoList = propertyNameAndDescList.get(index);
        LocalSetterInfo localSetterInfo = TransformerHelper.findSetterMethod(localSetterInfoList, Type.INT_TYPE.getDescriptor());
        if (localSetterInfo == null) {
            throw new RuntimeException("iinc var not right");
        }
        String propertyName = localSetterInfo.getName();
        String propertyDesc = localSetterInfo.getDesc();
        String setterName = localSetterInfo.getSetterName();
        String setterDesc = localSetterInfo.getSetterDesc();

        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, taskClassName, propertyName, propertyDesc);
        methodVisitor.visitVarInsn(ILOAD, iincInsnNode.incr);
        methodVisitor.visitInsn(IADD);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESTATIC, taskClassName, setterName, setterDesc, false);
    }

    @Override
    public void visitIntInsnNode(IntInsnNode intInsnNode, Frame<BasicValue> frame) {
        methodVisitor.visitIntInsn(intInsnNode.getOpcode(), intInsnNode.operand);
    }

    @Override
    public void visitLabelNode(LabelNode labelNode, Frame<BasicValue> frame) {
        methodVisitor.visitLabel(labelNode.getLabel());
    }

    @Override
    public void visitMultiANewArrayInsnNode(MultiANewArrayInsnNode multiANewArrayInsnNode, Frame<BasicValue> frame) {
        methodVisitor.visitMultiANewArrayInsn(multiANewArrayInsnNode.desc, multiANewArrayInsnNode.dims);
    }

    @Override
    public void visitLdcInsnNode(LdcInsnNode ldcInsnNode, Frame<BasicValue> frame) {
        methodVisitor.visitLdcInsn(ldcInsnNode.cst);
    }

    @Override
    public void visitTypeInsnNode(TypeInsnNode typeInsnNode, Frame<BasicValue> frame) {
        methodVisitor.visitTypeInsn(typeInsnNode.getOpcode(), typeInsnNode.desc);
    }

    @Override
    public void visitVarInsn(VarInsnNode varInsnNode, Frame<BasicValue> frame) {
        int index = varInsnNode.var;
        List<LocalSetterInfo> localSetterInfoList = propertyNameAndDescList.get(index);

        switch (varInsnNode.getOpcode()) {
            case ILOAD:
            case LLOAD:
            case FLOAD:
            case DLOAD:
            case ALOAD: {
                BasicValue basicValue = frame.getLocal(index);
                LocalSetterInfo localSetterInfo = TransformerHelper.findSetterMethod(localSetterInfoList, basicValue.getType().getDescriptor());
                if (localSetterInfo == null) {
                    throw new RuntimeException("load exception");
                }
                methodVisitor.visitVarInsn(ALOAD, 0);
                methodVisitor.visitFieldInsn(GETFIELD, taskClassName, localSetterInfo.getName(), localSetterInfo.getDesc());
                break;
            }
            case ISTORE:
            case LSTORE:
            case FSTORE:
            case DSTORE:
            case ASTORE: {
                BasicValue basicValue = frame.getStack(frame.getStackSize() - 1);
                LocalSetterInfo localSetterInfo = TransformerHelper.findSetterMethod(localSetterInfoList, basicValue.getType().getDescriptor());
                if (localSetterInfo == null) {
                    throw new RuntimeException("store exception");
                }

                methodVisitor.visitVarInsn(ALOAD, 0);
                methodVisitor.visitMethodInsn(INVOKESTATIC, taskClassName, localSetterInfo.getSetterName(), localSetterInfo.getSetterDesc(), false);
                break;
            }
            case RET:
                throw new IllegalArgumentException("un support opcode ret");
        }
    }

    @Override
    public void visitInvokeDynamicInsnNode(InvokeDynamicInsnNode invokeDynamicInsnNode, Frame<BasicValue> frame) {
        methodVisitor.visitInvokeDynamicInsn(
            invokeDynamicInsnNode.name,
            invokeDynamicInsnNode.desc,
            invokeDynamicInsnNode.bsm,
            invokeDynamicInsnNode.bsmArgs
        );
    }

    @Override
    public void visitFrameNode(FrameNode frameNode, Frame<BasicValue> frame) {

    }

    @Override
    public void visitJumpInsnNode(JumpInsnNode jumpInsnNode, Frame<BasicValue> frame) {
        methodVisitor.visitJumpInsn(jumpInsnNode.getOpcode(), jumpInsnNode.label.getLabel());
    }

    @Override
    public void visitInsnNode(InsnNode insnNode, Frame<BasicValue> frame) {
        switch (insnNode.getOpcode()) {
            case IRETURN:
            case LRETURN:
            case FRETURN:
            case DRETURN:
            case RETURN:
                throw new IllegalArgumentException("async function must return Future");
            case ARETURN:
            {
                AbstractInsnNode node = TransformerHelper.getPrevInsnNode(methodNode.instructions, insnIndex);
                if (!(node instanceof MethodInsnNode)) {
                    throw new IllegalArgumentException("async must appear in return statement");
                }
                MethodInsnNode mNode = (MethodInsnNode) node;
                if (!TransformerHelper.isAsyncCall(mNode)) {
                    throw new IllegalArgumentException("async must appear in return statement");
                }

                Type[] argTypes = Type.getArgumentTypes(mNode.desc);
                if (argTypes.length == 0) {
                    if (frame != null) {
                        int stackSize = frame.getStackSize();
                        int newMax = Math.max(stackSize + 2, frame.getMaxStackSize());
                        maxStackSize = Math.max(maxStackSize, newMax);
                    }

                    methodVisitor.visitVarInsn(ALOAD, 0);
                    methodVisitor.visitFieldInsn(GETFIELD, taskClassName, FUTURE_PROPERTY_NAME, FUTURE_IMPL_CLASS_DESC);
                    methodVisitor.visitInsn(ACONST_NULL);
                    methodVisitor.visitMethodInsn(
                        INVOKEVIRTUAL,
                        FUTURE_IMPL_CLASS_PATH,
                        "tryComplete",
                        "(Ljava/lang/Object;)Z",
                        false
                    );
                    if (frame != null) {
                        for (int j = 0; j < frame.getStackSize(); j++) {
                            methodVisitor.visitInsn(NOP);
                        }
                    }
                    methodVisitor.visitInsn(RETURN);
                } else {
                    if (frame != null) {
                        int stackSize = frame.getStackSize();
                        int newMax = Math.max(stackSize + 1, frame.getMaxStackSize());
                        maxStackSize = Math.max(maxStackSize, newMax);
                    }
                    methodVisitor.visitVarInsn(ALOAD, 0);
                    methodVisitor.visitFieldInsn(GETFIELD, taskClassName, FUTURE_PROPERTY_NAME, FUTURE_IMPL_CLASS_DESC);
                    methodVisitor.visitMethodInsn(
                        INVOKESTATIC,
                        taskClassName,
                        COMPLETE_METHOD_NAME,
                        COMPLETE_METHOD_DESC,
                        false
                    );
                    methodVisitor.visitInsn(RETURN);
                }
                break;
            }
            default:
                // LASTORE等操作数组的指令，不需要特殊处理
                methodVisitor.visitInsn(insnNode.getOpcode());
        }
    }

    @Override
    public void visitLookupSwitchInsnNode(LookupSwitchInsnNode lookupSwitchInsnNode, Frame<BasicValue> frame) {
        int[] keys = new int[lookupSwitchInsnNode.keys.size()];
        for (int j = 0; j < lookupSwitchInsnNode.keys.size(); j++) {
            keys[j] = lookupSwitchInsnNode.keys.get(j);
        }
        Label[] labels = new Label[lookupSwitchInsnNode.labels.size()];
        for (int j = 0; j < lookupSwitchInsnNode.labels.size(); j++) {
            labels[j] = lookupSwitchInsnNode.labels.get(j).getLabel();
        }
        methodVisitor.visitLookupSwitchInsn(
            lookupSwitchInsnNode.dflt.getLabel(),
            keys,
            labels
        );
    }

    public List<GenerateClassData> getGenerateClassDataList() {
        return generateClassDataList;
    }
}