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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.dom.*;

@SupportedAnnotationTypes("com.jinelei.ducktype.annotation.DuckType")
public class DuckTypeProcessor extends AbstractProcessor {
    private Messager messager;
    private String targetDirectory;
    private String targetGeneratedDirectory;
    // 安全地将 List 转换为 List<Object> 类型，不创建新对象
    @SuppressWarnings("rawtypes")
    private final Function<List, List<Object>> safeConvertToListObject;;
    // 是否有Override注解
    private final Predicate<MethodDeclaration> isOverrideAnnotationPresent;
    // 读取全部字节数组
    private final Function<FileObject, String> loadSourceFromSourceFile;
    // 加载FileObject
    private final BiFunction<StandardLocation, String, FileObject> loadFileObjectFromClass;
    // 获取类的包名
    final Function<String, String> resolveFilePathFromPackageName;
    // 获取方法签名，全限定名
    private final Function<ExecutableElement, String> getMethodSignature;
    // 修改源代码
    private final BiConsumer<TypeElement, TypeElement> modifySourceCode;
    // 写源代码
    private final BiConsumer<TypeElement, CompilationUnit> writeSources;

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public DuckTypeProcessor() {
        super();
        this.safeConvertToListObject = (List rawList) -> (List<Object>) rawList;
        this.isOverrideAnnotationPresent = mmm -> safeConvertToListObject
                .apply(mmm.modifiers()).stream().filter(i -> i instanceof Annotation)
                .noneMatch(i -> ((Annotation) i).getTypeName().toString().equals("Override"));
        this.loadSourceFromSourceFile = object -> {
            try (InputStream in = object.openInputStream()) {
                byte[] bytes = in.readAllBytes();
                return new String(bytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        this.loadFileObjectFromClass = (location, path) -> {
            try {
                return processingEnv.getFiler().getResource(location, "", path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        this.resolveFilePathFromPackageName = n -> n.replace('.', '/') + ".java";
        this.getMethodSignature = e -> Optional.ofNullable(e).map(i -> {
            String exceptions = i.getThrownTypes().stream()
                    .map(exceptionType -> Optional.ofNullable(exceptionType).map(TypeMirror::toString).orElse(""))
                    .collect(Collectors.joining(", "));
            String signature = "%s %s(%s)".formatted(
                    Optional.of(i).map(ExecutableElement::getReturnType).map(TypeMirror::toString).orElse(""),
                    i.getSimpleName().toString(),
                    i.getParameters().stream()
                            .map(t -> Optional.ofNullable(t).map(VariableElement::asType).map(TypeMirror::toString)
                                    .orElse(""))
                            .collect(Collectors.joining(", ")));
            if (!exceptions.isEmpty()) {
                signature += " throws " + exceptions;
            }
            return signature;
        }).orElse("");

        this.writeSources = (TypeElement classElement, CompilationUnit cu) -> {
            try {
                // 保存到target/generated-sources
                File dir = new File("ducktype-sample/target/generated-sources/annotations/com/jinelei/ducktype/sample");

                if (!dir.exists()) {
                    if (!dir.mkdirs()) {
                        throw new RuntimeException("创建目录失败: " + dir.getAbsolutePath());
                    }
                }
                try (FileWriter writer = new FileWriter(
                        new File(dir, classElement.getSimpleName().toString() + ".java"))) {
                    writer.write(cu.toString());
                } catch (Exception e) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "写入文件失败: " + e.getMessage());
                }
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR, "修改源代码失败: " + e.getMessage());
            }
        };
        this.modifySourceCode = (TypeElement classElement, TypeElement interfaceElement) -> {
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "为类【%s】添加实现接口【%s】".formatted(classElement.getSimpleName(), interfaceElement.getSimpleName()));
            // 使用Eclipse JDT解析源代码
            // 获取类的全限定名
            String qualifiedName = classElement.getQualifiedName().toString();
            FileObject sourceFile = loadFileObjectFromClass.apply(StandardLocation.SOURCE_PATH,
                    resolveFilePathFromPackageName.apply(qualifiedName));
            String sourceCode = loadSourceFromSourceFile.apply(sourceFile);
            messager.printMessage(Diagnostic.Kind.NOTE, "类源码：--------------------\n%s".formatted(sourceCode));

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
                        // 处理导入语句
                        List<Object> imports = safeConvertToListObject.apply(cu.imports());
                        ImportDeclaration importDeclaration = ast.newImportDeclaration();
                        importDeclaration.setName(ast.newName(interfaceElement.getQualifiedName().toString()));
                        imports.add(importDeclaration);

                        // 处理类的实现接口
                        List<Object> superInterfaceTypes = safeConvertToListObject.apply(t.superInterfaceTypes());
                        SimpleName typeName = ast.newSimpleName(interfaceElement.getSimpleName().toString());
                        SimpleType interfaceType = ast.newSimpleType(typeName);
                        superInterfaceTypes.add(interfaceType);

                        // 为实现的方法添加 @Override 注解
                        interfaceElement.getEnclosedElements().stream()
                                .filter(mm -> mm instanceof ExecutableElement)
                                .map(e -> ((ExecutableElement) e))
                                .forEach(mm -> {
                                    final String interfaceMethodSignature = getMethodSignature.apply(mm);
                                    messager.printMessage(Diagnostic.Kind.NOTE,
                                            "接口方法签名：%s".formatted(interfaceMethodSignature));
                                    Arrays.stream(t.getMethods())
                                            .filter(mmm -> interfaceMethodSignature
                                                    .equals(getMethodSignature.apply(mm)))
                                            .filter(isOverrideAnnotationPresent)
                                            .forEach(mmm -> {
                                                messager.printMessage(Diagnostic.Kind.NOTE,
                                                        "类方法签名：%s".formatted(getMethodSignature.apply(mm)));
                                                // 添加 @Override 注解
                                                NormalAnnotation overrideAnnotation = ast.newNormalAnnotation();
                                                overrideAnnotation.setTypeName(ast.newSimpleName("Override"));
                                                safeConvertToListObject.apply(mmm.modifiers()).add(0,
                                                        overrideAnnotation);
                                            });
                                });
                    });

            messager.printMessage(Diagnostic.Kind.NOTE,
                    "修改后的源代码：--------------------\n%s".formatted(cu.toString()));
            // 指定生成路径为 target/generated-sources/annotations
            this.writeSources.accept(classElement, cu);
        };
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        messager = processingEnv.getMessager();
        targetDirectory = processingEnv.getOptions().get("targetDirectory");
        targetGeneratedDirectory = "%s/generated-sources/annotations".formatted(targetDirectory);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // 扫描带DuckType注解的接口
        Set<TypeElement> interfaceTypeElements = new HashSet<>();
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.INTERFACE) {
                    interfaceTypeElements.add((TypeElement) element);
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "找到鸭子类型注解的接口：%s，当前数量: %d".formatted(element.getSimpleName(), interfaceTypeElements.size()));
                }
            }
        }

        // 扫描所有类
        for (Element element : roundEnv.getRootElements()) {
            if (element.getKind() == ElementKind.CLASS) {
                TypeElement classElement = (TypeElement) element;
                for (TypeElement interfaceElement : interfaceTypeElements) {
                    // 检查类是否已实现该接口
                    boolean alreadyImplemented = classElement.getInterfaces().stream()
                            .anyMatch(t -> t.toString().equals(interfaceElement.getQualifiedName().toString()));
                    if (!alreadyImplemented) {
                        // 检查方法签名是否匹配
                        List<String> classMethodSignatureList = classElement.getEnclosedElements().stream()
                                .filter(t -> t.getKind() == ElementKind.METHOD)
                                .map(t -> getMethodSignature.apply((ExecutableElement) t)).toList();
                        List<String> interfaceMethodSignatureList = interfaceElement.getEnclosedElements().stream()
                                .filter(t -> t.getKind() == ElementKind.METHOD)
                                .map(t -> getMethodSignature.apply((ExecutableElement) t)).toList();
                        boolean allInterfaceMethodsExist = new HashSet<>(classMethodSignatureList)
                                .containsAll(interfaceMethodSignatureList);
                        if (allInterfaceMethodsExist) {
                            // 为类添加实现接口的代码
                            this.modifySourceCode.accept(classElement, interfaceElement);
                        }
                    }
                }
            }
        }
        return true;
    }

}
