package com.whatswater.async;


import org.objectweb.asm.ClassWriter;

import java.util.List;
import java.util.Map;

public class GenerateClassData {
    private ClassWriter classWriter;
    private String className;
    private ClassType classType;
    private Map<Integer, List<LocalSetterInfo>> names;

    public GenerateClassData(ClassWriter classWriter, String className, ClassType classType, Map<Integer, List<LocalSetterInfo>> names) {
        this.classWriter = classWriter;
        this.className = className;
        this.classType = classType;
        this.names = names;
    }

    public ClassWriter getClassWriter() {
        return classWriter;
    }

    public void setClassWriter(ClassWriter classWriter) {
        this.classWriter = classWriter;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public ClassType getClassType() {
        return classType;
    }

    public void setClassType(ClassType classType) {
        this.classType = classType;
    }

    public Map<Integer, List<LocalSetterInfo>> getNames() {
        return names;
    }

    public void setNames(Map<Integer, List<LocalSetterInfo>> names) {
        this.names = names;
    }

    public enum ClassType {
        TASK,
        STACK_HOLDER
    }

}
