package com.jinelei.ducktype.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.dom.*;

@SupportedAnnotationTypes("com.jinelei.ducktype.annotation.DuckType")
public class DuckTypeProcessor extends AbstractProcessor {
    /**
     * 安全地将 List 转换为 List<Object> 类型，不创建新对象
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private final Function<List, List<Object>> safeConvertToListObject = (List rawList) -> (List<Object>) rawList;

    /**
     * 是否有Override注解
     */
    private final Predicate<MethodDeclaration> overrideAnnotationPresent = mmm -> safeConvertToListObject.apply(mmm.modifiers()).stream().filter(i -> i instanceof Annotation).noneMatch(i -> ((Annotation) i).getTypeName().toString().equals("Override"));

    /**
     * 读取全部字节数组
     */
    private final Function<FileObject, String> readSourceFromSourceFile = object -> {
        try (InputStream in = object.openInputStream()) {
            byte[] bytes = in.readAllBytes();
            return new String(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    };

    /**
     * 加载FileObject
     */
    private final BiFunction<StandardLocation, String, FileObject> loadSourceFileObject = (location, path) -> {
        try {
            return processingEnv.getFiler().getResource(location, "", path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    };

    /**
     * 获取方法签名，全限定名
     */
    private final Function<ExecutableElement, String> getMethodSignature = element -> {
        if (element == null) {
            return "";
        }
        // 获取返回类型的全限定名
        String returnType = Optional.of(element).map(ExecutableElement::getReturnType).map(TypeMirror::toString).orElse("");

        // 获取方法名
        String methodName = element.getSimpleName().toString();

        // 获取参数类型的全限定名
        String params = element.getParameters().stream()
                .map(t -> Optional.ofNullable(t).map(VariableElement::asType).map(TypeMirror::toString).orElse(""))
                .collect(Collectors.joining(", "));

        // 获取抛出异常类型的全限定名
        String exceptions = element.getThrownTypes().stream()
                .map(exceptionType -> Optional.ofNullable(exceptionType).map(TypeMirror::toString).orElse(""))
                .collect(Collectors.joining(", "));

        // 拼接方法签名
        String signature = "%s %s(%s)".formatted(returnType, methodName, params);
        if (!exceptions.isEmpty()) {
            signature += " throws " + exceptions;
        }
        return signature;
    };

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // 扫描带DuckType注解的接口
        Set<TypeElement> interfaceTypeElements = new HashSet<>();
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.INTERFACE) {
                    interfaceTypeElements.add((TypeElement) element);
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "找到鸭子类型注解的接口：%s，当前数量: %d".formatted(element.getSimpleName(), interfaceTypeElements.size()));
                }
            }
        }

        // 扫描所有类
        for (Element element : roundEnv.getRootElements()) {
            if (element.getKind() == ElementKind.CLASS) {
                TypeElement classElement = (TypeElement) element;
                for (TypeElement interfaceElement : interfaceTypeElements) {
                    // 检查类是否已实现该接口
                    boolean alreadyImplemented = classElement.getInterfaces().stream().anyMatch(t -> t.toString().equals(interfaceElement.getQualifiedName().toString()));
                    if (!alreadyImplemented) {
                        // 检查方法签名是否匹配
                        List<String> classMethodSignatureList = classElement.getEnclosedElements().stream().filter(t -> t.getKind() == ElementKind.METHOD).map(t -> getMethodSignature.apply((ExecutableElement) t)).toList();
                        List<String> interfaceMethodSignatureList = interfaceElement.getEnclosedElements().stream().filter(t -> t.getKind() == ElementKind.METHOD).map(t -> getMethodSignature.apply((ExecutableElement) t)).toList();
                        boolean allInterfaceMethodsExist = new HashSet<>(classMethodSignatureList).containsAll(interfaceMethodSignatureList);
                        if (allInterfaceMethodsExist) {
                            // 为类添加实现接口的代码
                            modifySourceCode(classElement, interfaceElement);
                        }
                    }
                }
            }
        }
        return true;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * 为类添加实现接口的代码
     *
     * @param classElement     类元素
     * @param interfaceElement 接口元素
     */
    private void modifySourceCode(TypeElement classElement, TypeElement interfaceElement) throws RuntimeException {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "为类【%s】添加实现接口【%s】".formatted(classElement.getSimpleName(), interfaceElement.getSimpleName()));
        final Function<String, String> fetchResolveNameFromPackageName = qualifiedName -> qualifiedName.replace('.', '/') + ".java";
        // 使用Eclipse JDT解析源代码
        // 获取类的全限定名
        String qualifiedName = classElement.getQualifiedName().toString();
        FileObject sourceFile = loadSourceFileObject.apply(StandardLocation.SOURCE_PATH, fetchResolveNameFromPackageName.apply(qualifiedName));
        String sourceCode = readSourceFromSourceFile.apply(sourceFile);
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "类源码：--------------------\n%s".formatted(sourceCode));

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(sourceCode.toCharArray());
        parser.setResolveBindings(true);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        AST ast = cu.getAST();
        safeConvertToListObject.apply(cu.types()).stream()
                .filter(t -> t instanceof TypeDeclaration)
                .map(t -> (TypeDeclaration) t)
                .filter(t -> t.getName().toString().equals(classElement.getSimpleName().toString()))
                .forEach(t -> {
                    // 添加接口实现
                    SimpleName typeName = ast.newSimpleName(interfaceElement.getSimpleName().toString());
                    SimpleType interfaceType = ast.newSimpleType(typeName);
                    safeConvertToListObject.apply(t.superInterfaceTypes()).add(interfaceType);

                    // 为实现的方法添加 @Override 注解
                    interfaceElement.getEnclosedElements().stream()
                            .filter(mm -> mm instanceof ExecutableElement)
                            .map(e -> ((ExecutableElement) e))
                            .forEach(mm -> {
                                final String interfaceMethodSignature = getMethodSignature.apply(mm);
                                Arrays.stream(t.getMethods())
                                        .filter(mmm -> interfaceMethodSignature.equals(getMethodSignature.apply(mm)))
                                        .filter(overrideAnnotationPresent)
                                        .forEach(mmm -> {
                                            // 添加 @Override 注解
                                            NormalAnnotation overrideAnnotation = ast.newNormalAnnotation();
                                            overrideAnnotation.setTypeName(ast.newSimpleName("Override"));
                                            safeConvertToListObject.apply(mmm.modifiers()).add(0, overrideAnnotation);
                                        });
                            });
                });

        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "修改后的源代码：--------------------\n%s".formatted(cu.toString()));

        // 使用 Filer 创建新的源文件
        File file = Optional.of(sourceFile)
                .map(FileObject::toUri)
                .map(File::new).orElseThrow(() -> new RuntimeException("源码文件未找到"));
        if (file.delete()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "删除原文件成功");
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "删除原文件失败");
        }
        try {
            if (file.createNewFile()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "创建新文件成功");
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "创建新文件失败: " + e.getMessage());
            throw new RuntimeException(e);
        }
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(cu.toString());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "修改后的代码写入文件成功");
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "修改后的代码写入文件失败: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void writeSources(TypeElement classElement, CompilationUnit cu) {
        try {
            // 保存到target/generated-sources
            File dir = new File("ducktype-sample/target/generated-sources/annotations");
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    throw new RuntimeException("创建目录失败: " + dir.getAbsolutePath());
                }
            }
            try (FileWriter writer = new FileWriter(new File(dir, classElement.getSimpleName().toString() + ".java"))) {
                writer.write(cu.toString());
            } catch (Exception e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "写入文件失败: " + e.getMessage());
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "修改源代码失败: " + e.getMessage());
        }
    }

}
