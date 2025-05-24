package com.jinelei.ducktype.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

@SupportedAnnotationTypes("com.jinelei.ducktype.annotation.DuckType")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class DuckTypeProcessor extends AbstractProcessor {

    public DuckTypeProcessor() {
        super();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "进入DuckTypeProcessor.process方法");
        // 扫描带DuckType注解的接口
        Set<TypeElement> duckTypeInterfaces = new HashSet<>();
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.INTERFACE) {
                    duckTypeInterfaces.add((TypeElement) element);
                }
            }
        }

        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "所有实现了DuckType注解的接口数量：%s".formatted(duckTypeInterfaces.size()));

        // 扫描所有类
        for (Element element : roundEnv.getRootElements()) {
            if (element.getKind() == ElementKind.CLASS) {
                TypeElement classElement = (TypeElement) element;
                for (TypeElement duckTypeInterface : duckTypeInterfaces) {
                    // 检查类是否已实现该接口
                    boolean alreadyImplemented = false;
                    for (TypeMirror iface : classElement.getInterfaces()) {
                        if (iface.toString().equals(duckTypeInterface.getQualifiedName().toString())) {
                            alreadyImplemented = true;
                            break;
                        }
                    }

                    if (!alreadyImplemented) {
                        // 检查方法签名是否匹配
                        boolean allMethodsMatch = true;
                        for (Element methodElement : duckTypeInterface.getEnclosedElements()) {
                            if (methodElement.getKind() == ElementKind.METHOD) {
                                boolean methodFound = false;
                                for (Element classMethodElement : classElement.getEnclosedElements()) {
                                    if (classMethodElement.getKind() == ElementKind.METHOD) {
                                        if (isMethodSignatureMatch((ExecutableElement) methodElement, (ExecutableElement) classMethodElement)) {
                                            methodFound = true;
                                            break;
                                        }
                                    }
                                }
                                if (!methodFound) {
                                    allMethodsMatch = false;
                                    break;
                                }
                            }
                        }

                        if (allMethodsMatch) {
                            // 为类添加实现接口的代码
                            addInterfaceImplementation(classElement, duckTypeInterface);
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * 检查两个方法签名是否匹配
     *
     * @param interfaceMethod 接口方法
     * @param classMethod     类方法
     * @return 是否匹配
     */
    private boolean isMethodSignatureMatch(ExecutableElement interfaceMethod, ExecutableElement classMethod) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "检查方法签名是否和Ducktype注解接口相似：接口信息：方法名：%s, 参数：%s, 返回值：%s".formatted(interfaceMethod.getSimpleName(), interfaceMethod.getParameters().size(), interfaceMethod.getReturnType().toString()));
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "检查方法签名是否和Ducktype注解接口相似：类信息：方法名：%s, 参数：%s, 返回值：%s".formatted(classMethod.getSimpleName(), classMethod.getParameters().size(), classMethod.getReturnType().toString()));

        if (!interfaceMethod.getSimpleName().equals(classMethod.getSimpleName())) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "检查方法签名是否和Ducktype注解接口相似：方法名不匹配");
            return false;
        }

        if (interfaceMethod.getParameters().size() != classMethod.getParameters().size()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "检查方法签名是否和Ducktype注解接口相似：参数个数不匹配");
            return false;
        }

        for (int i = 0; i < interfaceMethod.getParameters().size(); i++) {
            VariableElement interfaceParam = interfaceMethod.getParameters().get(i);
            VariableElement classParam = classMethod.getParameters().get(i);
            if (!interfaceParam.asType().toString().equals(classParam.asType().toString())) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "检查方法签名是否和Ducktype注解接口相似：参数类型不匹配");
                return false;
            }
        }

        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "检查方法签名是否和Ducktype注解接口相似：方法签名匹配");
        return true;
    }

    /**
     * 为类添加实现接口的代码
     *
     * @param classElement     类元素
     * @param interfaceElement 接口元素
     */
    private void addInterfaceImplementation(TypeElement classElement, TypeElement interfaceElement) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "为类添加实现接口的代码：类名: %s, 接口名: %s".formatted(classElement.getSimpleName(), interfaceElement.getQualifiedName()));
        // 使用Eclipse JDT解析源代码
        ASTParser parser = ASTParser.newParser(AST.JLS17);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(classElement.toString().toCharArray());
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "解析源代码：%s".formatted(classElement.toString()));
        parser.setResolveBindings(true);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "解析源代码成功： %b®".formatted(Objects.isNull(cu)));
        Arrays.stream(cu.getProblems()).filter(IProblem::isError).map(IProblem::getMessage)
                        .forEach(s-> processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "解析失败信息：%s".formatted(s)));

        // 查找类声明
        List<TypeDeclaration> types = cu.types();
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类声明数量：%s".formatted(cu.types().size()));
        for (TypeDeclaration type : types) {
            if (type.getName().toString().equals(classElement.getSimpleName().toString())) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "找到类声明：%s".formatted(type.getName().toString()));
                // 添加接口实现
                AST ast = cu.getAST();
                ASTRewrite rewrite = ASTRewrite.create(ast);
                Name interfaceName = ast.newSimpleName(interfaceElement.getSimpleName().toString());
                type.superInterfaceTypes().add(interfaceName);

                // 应用修改
                Document document = new Document(classElement.toString());
                TextEdit edits = rewrite.rewriteAST(document, null);
                try {
                    edits.apply(document);
                    // 保存修改后的代码
                    // 这里需要实现保存到文件的逻辑
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "修改后的代码：%s".formatted(document.get()));
                    // 保存到target/generated-sources
                    File file = new File("target/generated-sources");
                    if (!file.exists()) {
                        file.mkdirs();
                    }
                    file = new File(file, classElement.getSimpleName() + ".java");
                    if (!file.exists()) {
                        file.createNewFile();
                        try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
                            writer.write(document.get());
                            writer.flush();
                        } catch (Exception e) {
                            e.printStackTrace();
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "保存修改后的代码失败：%s".formatted(e.getMessage()));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            }
        }
    }
}
