
package cz.habarta.typescript.generator.emitter;

import org.apache.commons.lang3.StringUtils;
import cz.habarta.typescript.generator.*;
import cz.habarta.typescript.generator.compiler.EnumKind;
import cz.habarta.typescript.generator.compiler.EnumMemberModel;
import cz.habarta.typescript.generator.util.Utils;

import com.madakethealth.cred.support.MadaketResource;
import javax.ws.rs.Path;
import java.io.*;
import java.lang.reflect.Modifier;
import java.text.*;
import java.util.*;


public class Emitter {

    private final Settings settings;
    private Writer writer;
    private boolean forceExportKeyword;
    private int indent;

    public Emitter(Settings settings) {
        this.settings = settings;
    }

    public void emit(TsModel model, Writer output, String outputName, boolean closeOutput, boolean forceExportKeyword, int initialIndentationLevel) {
        this.writer = output;
        this.forceExportKeyword = forceExportKeyword;
        this.indent = initialIndentationLevel;
        if (outputName != null) {
            System.out.println("Writing declarations to: " + outputName);
        }
        emitFileComment();
        emitReferences();
        emitImports();
        emitModule(model);
        emitUmdNamespace();
        if (closeOutput) {
            close();
        }
    }

    private void emitFileComment() {
        if (!settings.noFileComment) {
            final String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            writeIndentedLine("// Generated using typescript-generator version " + TypeScriptGenerator.Version + " on " + timestamp + ".");
        }
    }

    private void emitReferences() {
        if (settings.referencedFiles != null && !settings.referencedFiles.isEmpty()) {
            writeNewLine();
            for (String reference : settings.referencedFiles) {
                writeIndentedLine("/// <reference path=" + quote(reference, settings) + " />");
            }
        }
    }

    private void emitImports() {
        if (settings.importDeclarations != null && !settings.importDeclarations.isEmpty()) {
            writeNewLine();
            for (String importDeclaration : settings.importDeclarations) {
                writeIndentedLine(importDeclaration + ";");
            }
        }
    }

    private void emitModule(TsModel model) {
        if (settings.outputKind == TypeScriptOutputKind.ambientModule) {
            writeNewLine();
            writeIndentedLine("declare module " + quote(settings.module, settings) + " {");
            indent++;
            emitNamespace(model);
            indent--;
            writeNewLine();
            writeIndentedLine("}");
        } else {
            emitNamespace(model);
        }
    }

    private void emitNamespace(TsModel model) {
        if (settings.namespace != null) {
            writeNewLine();
            String prefix = "";
            if (settings.outputFileType == TypeScriptFileType.declarationFile && settings.outputKind == TypeScriptOutputKind.global) {
                prefix = "declare ";
            }
            if (settings.outputKind == TypeScriptOutputKind.module) {
                prefix = "export ";
            }
            writeIndentedLine(prefix +  "namespace " + settings.namespace + " {");
            indent++;
            final boolean exportElements = settings.outputFileType == TypeScriptFileType.implementationFile;
            emitElements(model, exportElements, false);
            indent--;
            writeNewLine();
            writeIndentedLine("}");
        } else {
            final boolean exportElements = settings.outputKind == TypeScriptOutputKind.module;
            final boolean declareElements = settings.outputKind == TypeScriptOutputKind.global;
            emitElements(model, exportElements, declareElements);
        }
    }

    private void emitElements(TsModel model, boolean exportKeyword, boolean declareKeyword) {
        exportKeyword = exportKeyword || forceExportKeyword;
        emitMadaketResource();
        emitBeans(model, exportKeyword);
        emitTypeAliases(model, exportKeyword);
        emitNumberEnums(model, exportKeyword, declareKeyword);
        for (EmitterExtension emitterExtension : settings.extensions) {
            emitterExtension.emitElements(new EmitterExtension.Writer() {
                @Override
                public void writeIndentedLine(String line) {
                    Emitter.this.writeIndentedLine(line);
                }
            }, settings, exportKeyword, model);
        }
    }

    private void emitMadaketResource() {
        writeNewLine();
        writeIndentedLine("export interface " + MadaketResource.class.getSimpleName() + " {");
        indent++;
        writeIndentedLine("id: string;");
        writeIndentedLine("getResourceEndpoint(): string;");
        indent--;
        writeIndentedLine("}");
    }

    private void emitBeans(TsModel model, boolean exportKeyword) {
        for (TsBeanModel bean : model.getBeans()) {
            writeNewLine();
            emitComments(bean.getComments());

            List<String> implementsList = new ArrayList<String>();
            for(TsType implementEntry: bean.getImplementsList()) {
                implementsList.add(implementEntry + "");
            }
            if(bean.getOrigin() != null && bean.getOrigin().isAnnotationPresent(MadaketResource.class)) {
                implementsList.add(MadaketResource.class.getSimpleName());
            }

            final String abstractModifier = (bean.getOrigin() != null && Modifier.isAbstract(bean.getOrigin().getModifiers())) ? "abstract" : "";
            final String declarationType = bean.isClass() ? "class" : "interface";
            final String modifiers = (abstractModifier.length() > 0 && bean.isClass()) ? abstractModifier + " " + declarationType : declarationType;
            final String typeParameters = bean.getTypeParameters().isEmpty() ? "" : "<" + Utils.join(bean.getTypeParameters(), ", ")+ ">";
            final List<TsType> extendsList = bean.getExtendsList();
            final String extendsClause = extendsList.isEmpty() ? "" : " extends " + Utils.join(extendsList, ", ");
            final String implementsClause = implementsList.isEmpty() ? "" : " implements " + Utils.join(implementsList, ", ");
            writeIndentedLine(exportKeyword, modifiers + " " + bean.getName() + typeParameters + extendsClause + implementsClause + " {");
            indent++;
            for (TsPropertyModel property : bean.getProperties()) {
                emitProperty(property);
            }
            if (bean.getConstructor() != null) {
                emitCallable(bean.getConstructor());
            }
            for (TsMethodModel method : bean.getMethods()) {
                emitCallable(method);
            }
            if(settings.addConstructor && bean.isClass() && abstractModifier.length() == 0) {
                emitConstructor(bean);
            }
            if(bean.getOrigin() != null && bean.getOrigin().isAnnotationPresent(MadaketResource.class)) {
                emitResourcePath(bean);
            }
            indent--;
            writeIndentedLine("}");
        }
    }

    private void emitProperty(TsPropertyModel property) {
        emitComments(property.getComments());
        final TsType tsType = property.getTsType();
        final String questionMark = settings.declarePropertiesAsOptional || (tsType instanceof TsType.OptionalType) ? "?" : "";
        writeIndentedLine(quoteIfNeeded(property.getName(), settings) + questionMark + ": " + tsType.format(settings) + ";");
    }

    public static String quoteIfNeeded(String name, Settings settings) {
        return isValidIdentifierName(name) ? name : quote(name, settings);
    }

    public static String quote(String value, Settings settings) {
        return settings.quotes + value + settings.quotes;
    }

    // https://github.com/Microsoft/TypeScript/blob/master/doc/spec.md#2.2.2
    // http://www.ecma-international.org/ecma-262/6.0/index.html#sec-names-and-keywords
    public static boolean isValidIdentifierName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        final char start = name.charAt(0);
        if (!Character.isUnicodeIdentifierStart(start) && start != '$' && start != '_') {
            return false;
        }
        for (char c : name.substring(1).toCharArray()) {
            if (!Character.isUnicodeIdentifierPart(c) && c != '$' && c != '_' && c != '\u200C' && c != '\u200D') {
                return false;
            }
        }
        return true;
    }

    private void emitCallable(TsCallableModel method) {
        writeNewLine();
        emitComments(method.getComments());
        final List<String> parameters = new ArrayList<>();
        for (TsParameterModel parameter : method.getParameters()) {
            final String access = parameter.getAccessibilityModifier() != null ? parameter.getAccessibilityModifier().format() + " " : "";
            final String questionMark = (parameter.getTsType() instanceof TsType.OptionalType) ? "?" : "";
            final String type = parameter.getTsType() != null ? ": " + parameter.getTsType() : "";
            parameters.add(access + parameter.getName() + questionMark + type);
        }
        final String type = method.getReturnType() != null ? ": " + method.getReturnType() : "";
        final String signature = method.getName() + "(" + Utils.join(parameters, ", ") + ")" + type;
        if (method.getBody() != null) {
            writeIndentedLine(signature + " {");
            indent++;
            emitStatements(method.getBody());
            indent--;
            writeIndentedLine("}");
        } else {
            writeIndentedLine(signature + ";");
        }
    }

    private void emitConstructor(TsBeanModel bean) {
        writeNewLine();
        writeIndentedLine("constructor(values: Object = {}) {");
        indent++;
        if(bean.getExtendsList().size() > 0) {
            writeIndentedLine("super();");
        }
        writeIndentedLine("Object.assign(this, values);");
        emitInitializersIfNeeded(bean);
        indent--;
        writeIndentedLine("}");
    }

    private void emitInitializersIfNeeded(TsBeanModel bean) {
        for (TsPropertyModel property : bean.getProperties()) {
            if(StringUtils.equals(property.getTsType().format(settings), "Date")) {
                writeIndentedLine("this." + property.getName()
                        + " = new " + property.getTsType().format(settings) + "(this." + property.getName() + ");");
            }
        }
    }

    private void emitResourcePath(TsBeanModel bean) {
        if(bean.getOrigin() != null && bean.getOrigin().getAnnotation(MadaketResource.class) != null) {
            String path = bean.getOrigin().getAnnotation(MadaketResource.class).value();
            if(path != null && path.length() > 0) {
                writeNewLine();
                writeIndentedLine("public getResourceEndpoint(): string {");
                indent++;
                writeIndentedLine("return '"+ path + "';");
                indent--;
                writeIndentedLine("}");
            }
        }
    }

    private void emitStatements(List<TsStatement> statements) {
        for (TsStatement statement : statements) {
            if (statement instanceof TsReturnStatement) {
                final TsReturnStatement returnStatement = (TsReturnStatement) statement;
                if (returnStatement.getExpression() != null) {
                    writeIndentedLine("return " + returnStatement.getExpression().format(settings) + ";");
                } else {
                    writeIndentedLine("return;");
                }
            }
        }
    }

    private void emitTypeAliases(TsModel model, boolean exportKeyword) {
        for (TsAliasModel alias : model.getTypeAliases()) {
            writeNewLine();
            emitComments(alias.getComments());
            final String genericParameters = alias.getTypeParameters().isEmpty()
                    ? ""
                    : "<" + Utils.join(alias.getTypeParameters(), ", ") + ">";
            writeIndentedLine(exportKeyword, "type " + alias.getName() + genericParameters + " = " + alias.getDefinition().format(settings) + ";");
        }
    }

    private void emitNumberEnums(TsModel model, boolean exportKeyword, boolean declareKeyword) {
        final ArrayList<TsEnumModel<?>> enums = settings.mapEnum == EnumMapping.asNumberBasedEnum && !settings.areDefaultStringEnumsOverriddenByExtension()
                ? new ArrayList<>(model.getEnums())
                : new ArrayList<TsEnumModel<?>>(model.getEnums(EnumKind.NumberBased));
        for (TsEnumModel<?> enumModel : enums) {
            writeNewLine();
            emitComments(enumModel.getComments());
            writeIndentedLine(exportKeyword, (declareKeyword ? "declare " : "") + "enum " + enumModel.getName() + " {");
            indent++;
            for (EnumMemberModel<?> member : enumModel.getMembers()) {
                emitComments(member.getComments());
                final String initializer = enumModel.getKind() == EnumKind.NumberBased
                        ? " = " + member.getEnumValue()
                        : "";
                writeIndentedLine(member.getPropertyName() + initializer + ",");
            }
            indent--;
            writeIndentedLine("}");
        }
    }

    private void emitUmdNamespace() {
        if (settings.umdNamespace != null) {
            writeNewLine();
            writeIndentedLine("export as namespace " + settings.umdNamespace + ";");
        }
    }

    private void emitComments(List<String> comments) {
        if (comments != null) {
            writeIndentedLine("/**");
            for (String comment : comments) {
                writeIndentedLine(" * " + comment);
            }
            writeIndentedLine(" */");
        }
    }

    private void writeIndentedLine(boolean exportKeyword, String line) {
        writeIndentedLine((exportKeyword ? "export " : "") + line);
    }

    private void writeIndentedLine(String line) {
        try {
            if (!line.isEmpty()) {
                for (int i = 0; i < indent; i++) {
                    writer.write(settings.indentString);
                }
            }
            writer.write(line);
            writeNewLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeNewLine() {
        try {
            writer.write(settings.newline);
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
