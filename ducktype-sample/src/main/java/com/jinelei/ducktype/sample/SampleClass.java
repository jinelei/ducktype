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

    public static void main(String[] args) throws IOException, BadLocationException {
        String sourceCode = "public class MyClass {}";
        ASTParser parser = ASTParser.newParser(AST.JLS17);
        parser.setSource(sourceCode.toCharArray());
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);

        for (Object type : cu.types()) {
            if (type instanceof TypeDeclaration) {
                TypeDeclaration classDecl = (TypeDeclaration) type;
                AST ast = cu.getAST();
                ASTRewrite rewrite = ASTRewrite.create(ast);
                Name interfaceName = ast.newSimpleName("MyInterface");
                classDecl.superInterfaceTypes().add(interfaceName);

                Document document = new Document(sourceCode);
                TextEdit edits = rewrite.rewriteAST(document, null);
                edits.apply(document);

                try (FileWriter writer = new FileWriter("MyClass.java")) {
                    writer.write(document.get());
                }
            }
        }
    }

    public void execute() {
        System.out.println("SampleClass is executing.");
    }

}
