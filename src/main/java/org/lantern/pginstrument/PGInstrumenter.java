package org.lantern.pginstrument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javassist.ByteArrayClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.Modifier;

/**
 * <p>
 * Largely based on <a
 * href="http://java.dzone.com/articles/java-profiling-under-covers">this</a>
 * tutorial.
 * </p>
 */
public class PGInstrumenter implements ClassFileTransformer {
    private static final PGInstrumenter s_instance = new PGInstrumenter();

    private final List<String> ignoredPrefixes = Arrays.asList(new String[] {
            "sun.misc",
            "java.util.regex"
    });
    private final boolean debug;
    private final ClassPool classPool;
    private final Set<String> fullClasses = Collections
            .synchronizedSet(new HashSet<String>());
    private final Map<String, Set<String>> methodsByClass = new HashMap<String, Set<String>>();

    public PGInstrumenter() {
        this.classPool = ClassPool.getDefault();
        this.debug = "true".equalsIgnoreCase(System
                .getProperty("pginstrument.debug"));
        String additionalIgnoredPrefixes = System
                .getProperty("pginstrument.ignoredPrefixes");
        if (additionalIgnoredPrefixes != null) {
            for (String ignoredPrefix : additionalIgnoredPrefixes.split(",")) {
                ignoredPrefixes.add(ignoredPrefix);
            }
        }
    }

    public static PGInstrumenter getInstance() {
        return s_instance;
    }

    synchronized public void addClass(String clazz) {
        fullClasses.add(deArray(clazz));
    }

    synchronized public void addMethod(String clazz, String longMethodName) {
        clazz = deArray(clazz);
        Set<String> methods = methodsByClass.get(clazz);
        if (methods == null) {
            methods = new HashSet<String>();
            methodsByClass.put(clazz, methods);
        }
        methods.add(longMethodName.substring(clazz.length() + 1));
    }

    @Override
    synchronized public byte[] transform(ClassLoader loader, String className,
            Class classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        String dottedClassName = className.replace("/", ".");

        // Check whether we should ignore this class
        for (String ignoredPrefix : ignoredPrefixes) {
            if (dottedClassName.contains(ignoredPrefix)) {
                fullClasses.add(dottedClassName);
                return null;
            }
        }

        if (debug) {
            System.out.println("Transforming: " + dottedClassName);
        }
        try {
            classPool.insertClassPath(new ByteArrayClassPath(dottedClassName,
                    classfileBuffer));
            CtClass cc = classPool.get(dottedClassName);
            if (Modifier.isInterface(cc.getModifiers())) {
                if (debug) {
                    System.out
                            .println("Detected interface, returning unmodified: "
                                    + dottedClassName);
                }
                return null;
            }
            CtConstructor[] constructors = cc.getDeclaredConstructors();
            for (CtConstructor constructor : constructors) {
                int modifiers = constructor.getModifiers();
                boolean isAbstract = Modifier.isAbstract(modifiers);
                boolean isNative = Modifier.isNative(modifiers);
                if (!isAbstract && !isNative) {
                    constructor
                            .insertBefore("org.lantern.pginstrument.PGInstrumenter.getInstance().addClass(\""
                                    + constructor.getDeclaringClass().getName()
                                    + "\");");
                }
            }
            CtMethod[] methods = cc.getDeclaredMethods();
            for (CtMethod method : methods) {
                int modifiers = method.getModifiers();
                boolean isAbstract = Modifier.isAbstract(modifiers);
                boolean isNative = Modifier.isNative(modifiers);
                if (!isAbstract && !isNative) {
                    method.insertBefore("org.lantern.pginstrument.PGInstrumenter.getInstance().addMethod(\""
                            + method.getDeclaringClass().getName()
                            + "\", \""
                            + method.getLongName() + "\");");
                }
            }

            // return the new bytecode array:
            byte[] newClassfileBuffer = cc.toBytecode();
            return newClassfileBuffer;
        } catch (Exception e) {
            System.err.println(e.getMessage() + " transforming class "
                    + dottedClassName + "; returning uninstrumented class");
            fullClasses.add(dottedClassName);
        }
        return null;
    }

    synchronized private void writeProGuardConfig() {
        File outFile = new File("config.pro");
        PrintWriter out = null;
        try {
            out = new PrintWriter(new FileOutputStream(outFile));
            Set<String> classesForMethods = methodsByClass.keySet();
            for (String className : fullClasses) {
                if (!classesForMethods.contains(className)) {
                    try {
                        Class clazz = Class.forName(className);
                        String classOrInterface = clazz.isInterface() ? "interface"
                                : clazz.isEnum() ? "enum" : "class";
                        out.println(String.format("-keep %1$s %2$s {",
                                classOrInterface, className));
                        out.println("\t*;");
                        out.println("}");
                        out.println("");
                    } catch (Exception e) {
                        if (debug) {
                            System.err
                                    .println("Unable to load class: "
                                            + className);
                        }
                    }
                }
            }
            for (Map.Entry<String, Set<String>> entry : methodsByClass
                    .entrySet()) {
                String className = entry.getKey();
                out.println("-keep class " + className + " {");
                for (String method : entry.getValue()) {
                    out.println("\t*** " + method + ";");
                }
                out.println("}");
                out.println("");
            }
            System.out.println("Wrote ProGuard configuration to: "
                    + outFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Error writing proguard config: "
                    + e.getMessage());
            e.printStackTrace(System.err);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) {
                    System.err.println("Unable to close writer: "
                            + e.getMessage());
                }
            }
        }
    }

    public static void premain(String agentArgs, final Instrumentation inst)
            throws Exception {
        final PGInstrumenter instrumenter = PGInstrumenter.getInstance();
        inst.addTransformer(instrumenter, false);
        Class<?>[] loadedClasses = inst.getAllLoadedClasses();
        for (Class<?> clazz : loadedClasses) {
            instrumenter.fullClasses.add(clazz.getName());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                inst.removeTransformer(instrumenter);
                instrumenter.writeProGuardConfig();
            }
        }));
    }

    private static String deArray(String className) {
        return className.replaceAll("\\[?", "").replace(";", "");
    }
}
