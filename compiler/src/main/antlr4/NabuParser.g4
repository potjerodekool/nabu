parser grammar NabuParser;

@header {
package io.github.potjerodekool.nabu;
}

options {
    tokenVocab = NabuLexer;
}

compilationUnit
: packageDeclaration?
  importDeclaration*
  function*
;

packageDeclaration
: 'package' packageName ';'
;

importDeclaration
:	singleTypeImportDeclaration
;

singleTypeImportDeclaration
:	'import' typeName ';'
;

function
: 'fun' name=Identifier '(' params=functionParams ')' ':' returnType=unannClassOrInterfaceType '{'
    body=functionBody?
'}'
;

functionParams
: (functionParameter (',' functionParameter)*)?
;

functionParameter
: name=Identifier ':' type=unannType
;

functionBody
: statements=statement*
;

statement
: returnStatement
| localVariableDecleratorStatement
| expressionStatement
;

expressionStatement
: expression ';'
;

returnStatement
: 'return' exp=expression
;

expression
: lambdaExpression
| assignmentExpression
;

assignmentExpression
: conditionalExpression
;

conditionalExpression
: conditionalOrExpression
;

conditionalOrExpression
: conditionalAndExpression
| conditionalOrExpression '||' conditionalAndExpression
;

conditionalAndExpression
: inclusiveOrExpression
| conditionalAndExpression '&&' inclusiveOrExpression
;

inclusiveOrExpression
: exclusiveOrExpression
;

exclusiveOrExpression
: andExpression
;

andExpression
: equalityExpression
;

lambdaBody
: statement*
 expression?
;

lambdaExpression
: '{' name=Identifier ':' type=unannClassOrInterfaceType '->'
    exp=lambdaBody
    '}'
;

fieldAccessExpression
: target=Identifier '.' field=Identifier
;


equalityExpression
: relationalExpression
| left=equalityExpression oper='==' right=relationalExpression
| left=equalityExpression oper='!=' right=relationalExpression
;

relationalExpression
    : shiftExpression
    |	relationalExpression oper='<' shiftExpression
	|	relationalExpression oper='>' shiftExpression
	|	relationalExpression oper='<=' shiftExpression
	|	relationalExpression oper='>=' shiftExpression
	|	relationalExpression 'instanceof' referenceType
	;

shiftExpression
: additiveExpression
;

additiveExpression
: multiplicativeExpression
;

multiplicativeExpression
: unaryExpression
;

unaryExpression
: unaryExpressionNotPlusMinus
;

unaryExpressionNotPlusMinus
: postfixExpression
| '!' unaryExpression
;


typeIdentifier
: Identifier
;

packageName
: Identifier ('.' packageName)?
;

typeName
	:	Identifier
	|	packageOrTypeName '.' Identifier
	;

packageOrTypeName
	:	Identifier
	|	packageOrTypeName '.' Identifier
	;

unannType
: unannPrimitiveType
| unannClassOrInterfaceType
;

unannPrimitiveType
: numericType
| 'boolean'
;

unannClassOrInterfaceType
: (packageName '.')? typeIdentifier typeArguments?
;

typeArguments
    : '<' typeArgumentList '>'
    ;

typeArgumentList
    : typeArgument (',' typeArgument)*
    ;

typeArgument
    : referenceType
    | wildcard
    ;

wildcard
    : annotation* '?' wildcardBounds?
    ;

wildcardBounds
    : 'extends' referenceType
    | 'super' referenceType
    ;

annotation
    : normalAnnotation
    | markerAnnotation
    | singleElementAnnotation
;

normalAnnotation
    : '@' typeName '(' elementValuePairList? ')'
    ;

elementValuePairList
    : elementValuePair (',' elementValuePair)*
    ;

elementValuePair
    : identifier '=' elementValue
    ;

elementValue
    : conditionalExpression
    | elementValueArrayInitializer
    | annotation
    ;

elementValueArrayInitializer
    : '{' elementValueList? ','? '}'
    ;

elementValueList
    : elementValue (',' elementValue)*
    ;

markerAnnotation
    : '@' typeName
    ;

singleElementAnnotation
    : '@' typeName '(' elementValue ')'
    ;

localVariableDecleratorStatement
: localVariableDeclaration ';'
;

localVariableDeclaration
: localVariableType variableDeclarator '=' variableInitializer
;

variableInitializer
: expression asExpression?
;

localVariableType
: unannType
| 'var'
;

variableDeclarator
: variableDeclaratorId
;

variableDeclaratorId
: Identifier
;

literal
: StringLiteral
| BooleanLiteral
| NullLiteral
;

primitiveType
: (numericType | 'boolean')
;

numericType
: integralType
| floatingPointType
;

integralType
    : 'byte'
    | 'short'
    | 'int'
    | 'long'
    | 'char'
    ;

floatingPointType
    : 'float'
    | 'double'
    ;

referenceType
	:	classOrInterfaceType
	|	typeVariable
	|	arrayType
	;

coit
    : '.' annotation* typeIdentifier typeArguments? coit?
    ;

classOrInterfaceType
: (packageName '.')? annotation* typeIdentifier typeArguments? coit?
;

typeVariable
: annotation* typeIdentifier
;

arrayType
: primitiveType dims
  | classType dims
  | typeVariable dims
  ;

dims
: annotation* '[' ']' (annotation* '[' ']')*
;

classType
    : annotation* typeIdentifier typeArguments?
    | packageName '.' annotation* typeIdentifier typeArguments?
    | classOrInterfaceType '.' annotation* typeIdentifier typeArguments?
    ;

postfixExpression
: primary
| expressionName
;

primary
    : primaryNoNewArray
    ;

primaryNoNewArray
: literal
;

expressionName
    : (ambiguousName '.')? identifier
    ;

ambiguousName
    : identifier ('.' ambiguousName)?
    ;

identifier
    : Identifier
    ;

asExpression
: 'as' unannClassOrInterfaceType
;
