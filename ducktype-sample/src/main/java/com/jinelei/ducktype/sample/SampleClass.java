package com.jinelei.ducktype.sample;

import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.io.FileWriter;
import java.io.IOException;

public class SampleClass {

//    public static void main(String[] args) {
//        System.out.println("Hello world!");
//        System.out.println("SampleInterface is isAssignableFrom SampleClass: " + SampleInterface.class.isAssignableFrom(SampleClass.class));
//    }

//    public static void main(String[] args) throws IOException, BadLocationException {
//        String sourceCode = "public class MyClass {\n}\npublic static interface SampleInterface {\n void method();\n}";
//        ASTParser parser = ASTParser.newParser(AST.JLS17);
//        parser.setSource(sourceCode.toCharArray());
//        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
//        cu.recordModifications();
//
//        if (cu.types().size() != 2){
//            throw new RuntimeException("Wrong number of types");
//        }
//        TypeDeclaration tdMyClass = (TypeDeclaration) cu.types().get(0);
//        TypeDeclaration tdMyInterface = (TypeDeclaration) cu.types().get(1);
//
//        AST ast = cu.getAST();
//        ASTRewrite rewrite = ASTRewrite.create(ast);
//        SimpleType interfaceType = ast.newSimpleType(ast.newSimpleName("SampleInterface"));
//        tdMyClass.superInterfaceTypes().add(interfaceType);
//
////        Document document = new Document(sourceCode);
////        TextEdit edits = rewrite.rewriteAST(document, null);
////        TextEdit edits = cu.rewrite(document, null);
////        edits.apply(document);
//
//        try (FileWriter writer = new FileWriter("MyClass.java")) {
//            writer.write(cu.toString());
//        }
//        System.out.println("修改后的源码:\n" + cu.toString());
//    }

    public void execute() {
        System.out.println("SampleClass is executing.");
    }

}
