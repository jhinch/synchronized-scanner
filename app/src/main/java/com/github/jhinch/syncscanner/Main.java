package com.github.jhinch.syncscanner;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.AnnotationDefault;
import org.apache.bcel.classfile.AnnotationEntry;
import org.apache.bcel.classfile.Annotations;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.CodeException;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantDouble;
import org.apache.bcel.classfile.ConstantFieldref;
import org.apache.bcel.classfile.ConstantFloat;
import org.apache.bcel.classfile.ConstantInteger;
import org.apache.bcel.classfile.ConstantInterfaceMethodref;
import org.apache.bcel.classfile.ConstantInvokeDynamic;
import org.apache.bcel.classfile.ConstantLong;
import org.apache.bcel.classfile.ConstantMethodHandle;
import org.apache.bcel.classfile.ConstantMethodType;
import org.apache.bcel.classfile.ConstantMethodref;
import org.apache.bcel.classfile.ConstantModule;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantPackage;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.ConstantString;
import org.apache.bcel.classfile.ConstantUtf8;
import org.apache.bcel.classfile.ConstantValue;
import org.apache.bcel.classfile.Deprecated;
import org.apache.bcel.classfile.DescendingVisitor;
import org.apache.bcel.classfile.EnclosingMethod;
import org.apache.bcel.classfile.ExceptionTable;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.InnerClass;
import org.apache.bcel.classfile.InnerClasses;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.LineNumber;
import org.apache.bcel.classfile.LineNumberTable;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.LocalVariableTable;
import org.apache.bcel.classfile.LocalVariableTypeTable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.classfile.MethodParameters;
import org.apache.bcel.classfile.ParameterAnnotationEntry;
import org.apache.bcel.classfile.ParameterAnnotations;
import org.apache.bcel.classfile.Signature;
import org.apache.bcel.classfile.SourceFile;
import org.apache.bcel.classfile.StackMap;
import org.apache.bcel.classfile.StackMapEntry;
import org.apache.bcel.classfile.Synthetic;
import org.apache.bcel.classfile.Unknown;
import org.apache.bcel.classfile.Visitor;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

public class Main {

    public static void main(String... filenames) throws IOException {
        if (filenames.length == 0) {
            System.err.println("Usage: bin/app <file>...");
            System.exit(1);
        }
        List<Path> files = collectFiles(filenames);
        System.out.println("Found " + files.size() + " files");
        System.out.println("Beginning scan...");
        for (Path file : files) {
            if (file.toString().endsWith(".jar")) {
                scanJar(file);
            } else if (file.toString().endsWith(".class")) {
                scanClassFile(file);
            } else {
                System.out.println("ERROR: Unknown file type: " + file);
            }
        }
        System.out.println("Scan complete!");
    }

    private static List<Path> collectFiles(String... filenames) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String filename : filenames) {
            Path path = Paths.get(filename);
            if (Files.isDirectory(path)) {
                Files.walkFileTree(path, new FileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toString().endsWith(".jar") || file.toString().endsWith(".class")) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        throw exc;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (exc != null) {
                            throw exc;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                files.add(path);
            }
        }
        return files;
    }

    private static void scanJar(Path file) throws IOException {
        try (
                FileInputStream fileIn = new FileInputStream(file.toFile());
                JarInputStream jarIn = new JarInputStream(new BufferedInputStream(fileIn))
        ) {
            JarEntry jarEntry;
            while ((jarEntry = jarIn.getNextJarEntry()) != null) {
                if (jarEntry.getName().endsWith(".class")) {
                    scanClass(jarIn, jarEntry.getName());
                }
            }
        }
    }

    private static void scanClassFile(Path file) throws IOException {
        try (
                FileInputStream fileIn = new FileInputStream(file.toFile());
        ) {
            scanClass(new BufferedInputStream(fileIn), file.toString());
        }
    }

    private static void scanClass(InputStream in, String filename) throws IOException {
        ClassParser parser = new ClassParser(in, filename);
        Scanner scanner = new Scanner();
        JavaClass javaClass = parser.parse();
        DescendingVisitor visitor = new DescendingVisitor(javaClass, scanner);
        scanner.stack = visitor;
        visitor.visit();
    }

    private static class Scanner implements Visitor {

        private DescendingVisitor stack;

        @Override
        public void visitMethod(Method obj) {
            JavaClass javaClass = (JavaClass) stack.predecessor();
            if (obj.isSynchronized()) {

                System.out.println("synchronized method: " + javaClass.getClassName() + "#" + obj.getName());
            }
        }

        @Override
        public void visitCode(Code obj) {
            JavaClass javaClass = (JavaClass) stack.predecessor(1);
            Method method = (Method) stack.predecessor(0);
            InstructionList instructions = new InstructionList(obj.getCode());
            for (InstructionHandle instruction : instructions) {
                if (instruction.getInstruction().getOpcode() == Const.MONITORENTER) {
                    System.out.println("synchronized block: "
                            + javaClass.getClassName() + "#" + method.getName()
                            + " line " + obj.getLineNumberTable().getSourceLine(instruction.getPosition())
                    );
                }
            }
        }

        @Override
        public void visitAnnotation(Annotations obj) {
        }

        @Override
        public void visitAnnotationDefault(AnnotationDefault obj) {

        }

        @Override
        public void visitAnnotationEntry(AnnotationEntry obj) {

        }

        @Override
        public void visitBootstrapMethods(BootstrapMethods obj) {

        }

        @Override
        public void visitCodeException(CodeException obj) {

        }

        @Override
        public void visitConstantClass(ConstantClass obj) {

        }

        @Override
        public void visitConstantDouble(ConstantDouble obj) {

        }

        @Override
        public void visitConstantFieldref(ConstantFieldref obj) {

        }

        @Override
        public void visitConstantFloat(ConstantFloat obj) {

        }

        @Override
        public void visitConstantInteger(ConstantInteger obj) {

        }

        @Override
        public void visitConstantInterfaceMethodref(ConstantInterfaceMethodref obj) {

        }

        @Override
        public void visitConstantInvokeDynamic(ConstantInvokeDynamic obj) {

        }

        @Override
        public void visitConstantLong(ConstantLong obj) {

        }

        @Override
        public void visitConstantMethodHandle(ConstantMethodHandle obj) {

        }

        @Override
        public void visitConstantMethodref(ConstantMethodref obj) {

        }

        @Override
        public void visitConstantMethodType(ConstantMethodType obj) {

        }

        @Override
        public void visitConstantModule(ConstantModule constantModule) {

        }

        @Override
        public void visitConstantNameAndType(ConstantNameAndType obj) {

        }

        @Override
        public void visitConstantPackage(ConstantPackage constantPackage) {

        }

        @Override
        public void visitConstantPool(ConstantPool obj) {

        }

        @Override
        public void visitConstantString(ConstantString obj) {

        }

        @Override
        public void visitConstantUtf8(ConstantUtf8 obj) {

        }

        @Override
        public void visitConstantValue(ConstantValue obj) {

        }

        @Override
        public void visitDeprecated(Deprecated obj) {

        }

        @Override
        public void visitEnclosingMethod(EnclosingMethod obj) {

        }

        @Override
        public void visitExceptionTable(ExceptionTable obj) {

        }

        @Override
        public void visitField(Field obj) {

        }

        @Override
        public void visitInnerClass(InnerClass obj) {

        }

        @Override
        public void visitInnerClasses(InnerClasses obj) {

        }

        @Override
        public void visitJavaClass(JavaClass obj) {

        }

        @Override
        public void visitLineNumber(LineNumber obj) {

        }

        @Override
        public void visitLineNumberTable(LineNumberTable obj) {

        }

        @Override
        public void visitLocalVariable(LocalVariable obj) {

        }

        @Override
        public void visitLocalVariableTable(LocalVariableTable obj) {

        }

        @Override
        public void visitLocalVariableTypeTable(LocalVariableTypeTable obj) {

        }

        @Override
        public void visitMethodParameters(MethodParameters obj) {

        }

        @Override
        public void visitParameterAnnotation(ParameterAnnotations obj) {

        }

        @Override
        public void visitParameterAnnotationEntry(ParameterAnnotationEntry obj) {

        }

        @Override
        public void visitSignature(Signature obj) {

        }

        @Override
        public void visitSourceFile(SourceFile obj) {

        }

        @Override
        public void visitStackMap(StackMap obj) {

        }

        @Override
        public void visitStackMapEntry(StackMapEntry obj) {

        }

        @Override
        public void visitSynthetic(Synthetic obj) {

        }

        @Override
        public void visitUnknown(Unknown obj) {

        }
    }


}
