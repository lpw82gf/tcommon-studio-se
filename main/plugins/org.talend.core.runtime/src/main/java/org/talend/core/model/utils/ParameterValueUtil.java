// ============================================================================
//
// Copyright (C) 2006-2014 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.core.model.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;

import org.apache.oro.text.regex.MalformedPatternException;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.PatternCompiler;
import org.apache.oro.text.regex.PatternMatcher;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;
import org.apache.oro.text.regex.Perl5Substitution;
import org.apache.oro.text.regex.Substitution;
import org.apache.oro.text.regex.Util;
import org.eclipse.emf.common.util.EList;
import org.eclipse.swt.graphics.Point;
import org.talend.core.model.context.UpdateContextVariablesHelper;
import org.talend.core.model.process.EParameterFieldType;
import org.talend.core.model.process.IElementParameter;
import org.talend.core.utils.TalendQuoteUtils;
import org.talend.designer.core.model.utils.emf.talendfile.ElementParameterType;
import org.talend.designer.core.model.utils.emf.talendfile.ElementValueType;

/**
 * cli class global comment. Detailled comment
 */
public final class ParameterValueUtil {

    private ParameterValueUtil() {
    }

    @SuppressWarnings("unchecked")
    public static void renameValues(final IElementParameter param, final String oldName, final String newName) {
        if (param == null || oldName == null || newName == null) {
            return;
        }
        // boolean flag = true;
        // if (param.getFieldType() == EParameterFieldType.MEMO_SQL) {
        // flag = false;
        // }
        if (param.getValue() instanceof String) { // for TEXT / MEMO etc..
            String value = (String) param.getValue();
            if (value.contains(oldName)) {
                // param.setValue(value.replaceAll(oldName, newName));
                // String newValue = renameValues(value, oldName, newName, flag);
                String newValue = splitQueryData(oldName, newName, value);
                if (!value.equals(newValue)) {
                    param.setValue(newValue);
                }
            }
        } else if (param.getValue() instanceof List) { // for TABLE
            List<Map<String, Object>> tableValues = (List<Map<String, Object>>) param.getValue();
            for (Map<String, Object> line : tableValues) {
                for (String key : line.keySet()) {
                    Object cellValue = line.get(key);
                    if (cellValue instanceof String) { // cell is text so
                        // rename data if
                        // needed
                        String value = (String) cellValue;
                        if (value.contains(oldName)) {
                            // line.put(key, value.replaceAll(oldName,
                            // newName));
                            // String newValue = renameValues(value, oldName, newName, flag);
                            String newValue = splitQueryData(oldName, newName, value);
                            if (!value.equals(newValue)) {
                                line.put(key, newValue);
                            }
                        }
                    }
                }
            }
        }
    }

    public static String renameValues(final String value, final String oldName, final String newName, boolean flag) {
        if (value == null || oldName == null || newName == null) {
            return value; // keep original value
        }
        PatternCompiler compiler = new Perl5Compiler();
        Perl5Matcher matcher = new Perl5Matcher();
        matcher.setMultiline(true);
        Perl5Substitution substitution = new Perl5Substitution(newName + "$2", Perl5Substitution.INTERPOLATE_ALL); //$NON-NLS-1$

        Pattern pattern;
        try {
            pattern = compiler.compile(getQuotePattern(oldName));
        } catch (MalformedPatternException e) {
            return value; // keep original value
        }

        if (matcher.contains(value, pattern)) {
            // replace
            String returnValue = "";
            if (value.contains(TalendQuoteUtils.getQuoteChar()) && !flag) {
                // returnValue = splitQueryData(matcher, pattern, substitution, value, Util.SUBSTITUTE_ALL);
                returnValue = splitQueryData(oldName, newName, value);
            } else {
                returnValue = Util.substitute(matcher, pattern, substitution, value, Util.SUBSTITUTE_ALL);
            }
            return returnValue;

        }
        return value; // keep original value

    }

    // function before TDI-29092 modify,this function seems only rename variables in context,I put this funciton back
    // incase any problem with the new function and we can refer the old one to check the problem.
    public static String splitQueryData(PatternMatcher matcher, Pattern pattern, Substitution sub, String value, int numSubs) {
        String[] split = value.split("\"");
        int i = 0;
        String replace = "";
        for (String s : split) {
            if (i % 2 == 0) {
                replace = s;
                if (i != 0) {
                    if (matcher.contains(value, pattern)) {
                        replace = split[i].toString();
                        split[i] = Util.substitute(matcher, pattern, sub, replace, numSubs);
                    }
                }
            }
            i++;
        }
        String returnValue = "";
        for (int t = 1; t < split.length; t++) {
            if (t % 2 == 0) {
                returnValue += split[t];
            } else {
                returnValue += "\"" + split[t] + "\"";
            }
        }
        return returnValue;
    }

    // for bug 12594 split "; for bug 29092(it has JUnits)
    public static String splitQueryData(String oldName, String newName, String value) {
        // example:"drop table "+context.oracle_schema+".\"TDI_26803\""
        // >>>>>>>>_*_(const)__ _____*_(varible)_______ __*_(const)___

        /**
         * <b>NOTE</b>: This [inputString] variable only used to debug, should not use it in product
         */
        inputString = value;

        final int length = value.length();
        // quotaStrings which stores the start and end point for all const strings in the value
        LinkedHashMap<Integer, Integer> quotaStrings = new LinkedHashMap<Integer, Integer>();
        // List<Point> functionNameAreas = new ArrayList<Point>();
        List<FunctionInfo> functions = new ArrayList<FunctionInfo>();

        // get and store all start and end point of const strings
        int start = -1;
        int end = -2;
        char ch;
        for (int i = 0; i < length; i++) {
            ch = value.charAt(i);
            if (ch == '\"') {
                // in case of cases :
                // case 1 : [ "select * from " + context.table + " where value = \"context.table\"" ]
                // case 2 : [ "select * from " + context.table + " where value = \"\\" + context.table +
                // "\\context.table\"" ]
                if (isEscapeSequence(value, i)) {
                    continue;
                }

                // [0 <= start] >> in case the first const String position compute error
                if (0 <= start && end < start) {
                    end = i;
                    quotaStrings.put(start, end);
                } else {
                    start = i;
                }
            }
        }

        {
            // in case the value has not complete quota
            // exapmle > "select a,context.b from " + context.b + "where value = context.b

            // **but** maybe more impossible truth is that
            // they write this(context.b) just want to use it as a varible...
            // so maybe should not set the string behind the quota as a const by default..

            // ---*--- the following code is set the string behind the quota as a const

            // if (0 <= start && end < start) {
            // end = length - 1;
            // quotaStrings.put(start, end);
            // }
        }

        // find the varible string, do replace, then concat them
        StringBuffer strBuffer = new StringBuffer();
        String subString = null;
        int vStart = 0;
        int vEnd = 0;
        int methodMaxIndex = -1;
        vStart = 0;
        vEnd = 0;
        start = 0;
        end = 0;
        for (Entry<Integer, Integer> entry : quotaStrings.entrySet()) {
            start = entry.getKey();
            end = entry.getValue() + 1;
            vEnd = start;
            if (vStart != start) {
                subString = value.substring(vStart, vEnd);
                calcMethodArea(subString, value, vStart, functions);
            }
            vStart = end;
        }
        vStart = 0;
        vEnd = 0;
        start = 0;
        end = 0;
        for (Entry<Integer, Integer> entry : quotaStrings.entrySet()) {
            start = entry.getKey();
            end = entry.getValue() + 1;
            vEnd = start;
            if (vStart == start) {
                // const string follow with const string, maybe won't happen...
                // get the const string
                subString = value.substring(start, end);
                if (start < methodMaxIndex) {
                    subString = subString.replaceAll(oldName, newName);
                }
            } else {
                // get the varible string, do replace, then append it
                subString = value.substring(vStart, vEnd);
                // calcMaxIndex = calcMethodArea(subString, value, vStart, functions, methodMaxIndex);

                if (methodMaxIndex < start) {
                    methodMaxIndex = FunctionInfo.getMaxIndexForCurrentParentFunction(start, functions);
                }

                String replacedString = doVaribleReplace(oldName, newName, value, functions, vStart, vEnd);
                strBuffer.append(replacedString);

                // get the const string
                // deal with: context.getProperty("test") + "test"
                subString = value.substring(start, end);
                if (start < methodMaxIndex) {
                    FunctionInfo function = FunctionInfo.getParentFunctionFromList(start, end, functions);
                    Point funcNameArea = function.getNameArea();
                    String functionName = value.substring(funcNameArea.x, funcNameArea.y);
                    if (functionName.matches("^globalMap\\..+")) { //$NON-NLS-1$
                        subString = subString.replaceAll(oldName, newName);
                    } else {
                        if (subString.equals("\"" + oldName + "\"")) { //$NON-NLS-1$ //$NON-NLS-2$
                            subString = "\"" + newName + "\""; //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                }
            }
            // append the const string
            strBuffer.append(subString);
            // update the varible string start point
            vStart = end;
        }

        // in case the last string of the value is a varible string
        // then get it, and do replace, finally append it.
        if (vStart < length) {
            vEnd = length;
            String replacedString = doVaribleReplace(oldName, newName, value, functions, vStart, vEnd);
            strBuffer.append(replacedString);
        }

        return strBuffer.toString();
    }

    /**
     * <b>NOTE</b>: This variable only used to debug, should not use it in product
     */
    private static String inputString = ""; //$NON-NLS-1$

    private static class FunctionInfo {

        private Point nameArea;

        private Point paramArea;

        private List<FunctionInfo> subFunctions = new ArrayList<FunctionInfo>();

        public FunctionInfo(Point _nameArea) {
            nameArea = _nameArea;
        }

        public Point getNameArea() {
            return this.nameArea;
        }

        public void setParamArea(Point paramArea) {
            this.paramArea = paramArea;
        }

        public Point getParamArea() {
            return this.paramArea;
        }

        public int getFuncAreaMinIndex() {
            return nameArea.x;
        }

        public int getFuncAreaMaxIndex() {
            return paramArea.y;
        }

        public FunctionInfo getParentFunction(int x, int y) {
            FunctionInfo parentFunction = null;

            int minDistance = -1;
            for (FunctionInfo funcInfo : subFunctions) {
                int paramX = funcInfo.paramArea.x;
                int paramY = funcInfo.paramArea.y;
                if (paramX <= x && y <= paramY) {
                    if (!funcInfo.subFunctions.isEmpty()) {
                        FunctionInfo retFuncInfo = funcInfo.getParentFunction(x, y);
                        if (retFuncInfo != null) {
                            int distance = x - retFuncInfo.paramArea.x + retFuncInfo.paramArea.y - y;
                            if (minDistance < 0 || distance < minDistance) {
                                minDistance = distance;
                                parentFunction = retFuncInfo;
                            }
                            continue;
                        }
                    }
                    int distance = x - paramX + paramY - y;
                    if (minDistance < 0 || distance < minDistance) {
                        minDistance = distance;
                        parentFunction = funcInfo;
                    }
                }
            }
            if (minDistance < 0) {
                int paramX = this.paramArea.x;
                int paramY = this.paramArea.y;
                if (paramX <= x && y <= paramY) {
                    parentFunction = this;
                }
            }

            return parentFunction;
        }

        public void addSubFunction(FunctionInfo subFunc) {
            this.subFunctions.add(subFunc);
        }

        public List<FunctionInfo> getSubFunctions() {
            return this.subFunctions;
        }

        public static void addFunctionToList(FunctionInfo funcInfo, List<FunctionInfo> functionList) {
            if (functionList != null) {
                for (FunctionInfo iFuncInfo : functionList) {
                    FunctionInfo parentFuncInfo = iFuncInfo.getParentFunction(funcInfo.getFuncAreaMinIndex(),
                            funcInfo.getFuncAreaMaxIndex());
                    if (parentFuncInfo != null) {
                        parentFuncInfo.addSubFunction(funcInfo);
                        return;
                    }
                }
                // if can not found, add it to the functionList dirrectly
                functionList.add(funcInfo);
            }
        }

        public static FunctionInfo getParentFunctionFromList(int x, int y, List<FunctionInfo> functionList) {
            if (functionList != null) {
                for (FunctionInfo funcInfo : functionList) {
                    FunctionInfo parentFunction = funcInfo.getParentFunction(x, y);
                    if (parentFunction != null) {
                        return parentFunction;
                    }
                }
            }
            return null;
        }

        public static int getMaxIndexForCurrentParentFunction(int index, List<FunctionInfo> functionList) {
            int maxIndex = -1;
            if (functionList != null) {
                for (FunctionInfo funcInfo : functionList) {
                    Point paramArea = funcInfo.getParamArea();
                    if (paramArea.x <= index && index <= paramArea.y) {
                        return paramArea.y;
                    }
                }
            }
            return maxIndex;
        }

        public static List<FunctionInfo> getFunctionsInSpecifiedAreaFromList(int x, int y, List<FunctionInfo> functionList) {
            List<FunctionInfo> findedList = new ArrayList<FunctionInfo>();
            for (FunctionInfo funcInfo : functionList) {
                Point nameArea = funcInfo.getNameArea();
                if (x <= nameArea.x && nameArea.y <= y) {
                    findedList.add(funcInfo);
                    List<FunctionInfo> subFuncs = funcInfo.getSubFunctions();
                    if (subFuncs != null && !subFuncs.isEmpty()) {
                        List<FunctionInfo> findedListInSubFuncs = getFunctionsInSpecifiedAreaFromList(x, y, subFuncs);
                        if (findedListInSubFuncs != null && !findedListInSubFuncs.isEmpty()) {
                            findedList.addAll(findedListInSubFuncs);
                        }
                    }
                } else {
                    break;
                }
            }
            return findedList;
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.lang.Object#toString()
         */
        @Override
        public String toString() {
            if (getFuncAreaMaxIndex() < getFuncAreaMinIndex()) {
                return "not available"; //$NON-NLS-1$
            }
            return inputString.substring(getFuncAreaMinIndex(), getFuncAreaMaxIndex() + 1);
        }
    }

    /**
     * DOC cmeng Comment method "doVaribleReplace".
     * 
     * @param oldName
     * @param newName
     * @param value
     * @param functionNameAreas
     * @param vStart
     * @param vEnd
     */
    private static String doVaribleReplace(String oldName, String newName, String value, List<FunctionInfo> functions,
            int vStart, int vEnd) {
        if (value.trim().isEmpty()) {
            return value;
        }

        StringBuffer replacedString = new StringBuffer();
        int replaceableStart = vStart;
        int replaceableEnd = vEnd;
        List<FunctionInfo> replaceableFunctions = FunctionInfo.getFunctionsInSpecifiedAreaFromList(vStart, vEnd, functions);
        for (FunctionInfo funcInfo : replaceableFunctions) {
            Point functionNameArea = funcInfo.getNameArea();
            if (vEnd <= functionNameArea.x) {
                break;
            }
            if (functionNameArea.y <= vStart) {
                continue;
            }
            if (replaceableStart < functionNameArea.x) {
                replaceableEnd = functionNameArea.x;
                String replaceableString = value.substring(replaceableStart, replaceableEnd);
                replacedString.append(doReplace(oldName, newName, replaceableString));
                replacedString.append(doReplace(oldName, newName, value.substring(functionNameArea.x, functionNameArea.y)));
            } else {
                replacedString.append(doReplace(oldName, newName, value.substring(functionNameArea.x, functionNameArea.y)));
            }
            replaceableStart = functionNameArea.y;
        }
        if (replaceableStart < vEnd) {
            String replaceableString = value.substring(replaceableStart, vEnd);
            replacedString.append(doReplace(oldName, newName, replaceableString));
        }
        return replacedString.toString();
    }

    private static String doReplace(String oldName, String newName, String value) {
        if (value.trim().isEmpty()) {
            return value;
        }

        String vOldName = oldName.replaceAll("\\.", "\\\\."); //$NON-NLS-1$ //$NON-NLS-2$

        // ((\b\w+\s*\.\s*)+schema(\s*\.\s*\w+)*)|((\b\w+\s*\.\s*)*schema(\s*\.\s*\w+)+)
        String regex = "((\\b\\w+\\s*\\.\\s*)+" + vOldName + "\\b)|(\\b" + vOldName + "\\s*\\()"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // obtain all varibles
        String[] split = value.split(regex);
        Map<String, String> replacedStrings = new HashMap<String, String>();
        StringBuffer returnValue = new StringBuffer();
        // replace the variables & store both value of old and new
        for (String s : split) {
            if (s.contains(oldName)) {
                replacedStrings.put(s, s.replaceAll("\\b" + oldName + "\\b", newName)); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                replacedStrings.put(s, s);
            }
        }
        if (split.length == 1) {
            returnValue.append(replacedStrings.get(split[0]));
        }
        // obtain consts & concat the consts with the variables
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        Matcher matcher = pattern.matcher(value);
        if (matcher.find()) {
            int x = matcher.start();
            int y = matcher.end();
            int curPos = 0;
            int valueLength = value.length();
            String oldFill = null;
            String newFill = null;

            String subRegEx = "^" + vOldName + "(\\s*\\.\\s*\\w+)+"; //$NON-NLS-1$ //$NON-NLS-2$
            java.util.regex.Pattern subPattern = java.util.regex.Pattern.compile(subRegEx);

            while (true) {
                if (curPos == valueLength) {
                    break;
                }
                if (curPos < x) {
                    oldFill = value.substring(curPos, x);
                    if ((newFill = replacedStrings.get(oldFill)) != null) {
                        returnValue.append(newFill);
                    } else {
                        returnValue.append(oldFill);
                    }
                    curPos = x;
                    continue;
                }
                String matchedString = matcher.group();
                Matcher subMatcher = subPattern.matcher(matchedString);
                if (subMatcher.find()) {
                    returnValue.append(matchedString.replaceFirst(vOldName, newName));
                } else {
                    returnValue.append(matchedString);
                }
                curPos = y;
                if (!matcher.find()) {
                    x = valueLength;
                } else {
                    x = matcher.start();
                    y = matcher.end();
                }
            }
        }
        return returnValue.toString();
    }

    /**
     * DOC cmeng Comment method "isEscapeSequence".
     * 
     * @param value
     * @param i
     * @return
     */
    private static boolean isEscapeSequence(String value, int i) {
        boolean isEscapeSequence = false;
        for (int index = i; 0 < index; index--) {
            if (value.charAt(index - 1) == '\\') {
                isEscapeSequence = !isEscapeSequence;
            } else {
                break;
            }
        }
        return isEscapeSequence;
    }

    private static int calcMethodArea(String varibleString, String wholeString, int beginIndex, List<FunctionInfo> functions) {
        // globalMap.get(...)
        //        String regex = "\\b\\S*\\s*\\.\\s*\\S*\\s*\\(\\z"; //$NON-NLS-1$
        // maybe get(...) also is target
        String regex = "\\b[\\w\\.]*?\\s*\\("; //$NON-NLS-1$

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        Matcher matcher = pattern.matcher(varibleString);
        int i = 0;
        int currentMaxIndex = i;
        while (matcher.find()) {
            boolean isInQuota = false;
            int parenthesisNum = 0;
            int matchedStart = matcher.start();
            int matchedEnd = matcher.end();
            Point functionNameArea = new Point(beginIndex + matchedStart, beginIndex + matchedEnd);
            FunctionInfo funcInfo = new FunctionInfo(functionNameArea);

            Point functionParamArea = new Point(-1, -1);
            funcInfo.setParamArea(functionParamArea);

            i = beginIndex + matchedEnd;
            functionParamArea.x = i;

            for (; i < wholeString.length(); i++) {
                char ch = wholeString.charAt(i);
                if (ch == '\"' && !isEscapeSequence(wholeString, i)) {
                    isInQuota = !isInQuota;
                }
                if (isInQuota) {
                    continue;
                }
                if (ch == '(') {
                    parenthesisNum++;
                } else if (ch == ')') {
                    parenthesisNum--;
                }
                if (parenthesisNum < 0) {
                    break;
                }
            }
            functionParamArea.y = i;
            FunctionInfo.addFunctionToList(funcInfo, functions);
            if (currentMaxIndex < i) {
                currentMaxIndex = i;
            }
        }
        return currentMaxIndex;
    }

    public static boolean isUseData(final IElementParameter param, final String name) {
        if (param == null || name == null) {
            return false;
        }
        if (param.getValue() instanceof String) { // for TEXT / MEMO etc..
            String value = (String) param.getValue();
            if (ParameterValueUtil.valueContains(value, name)) {
                return true;
            }
        } else if (param.getValue() instanceof List) { // for TABLE
            List<Map<String, Object>> tableValues = (List<Map<String, Object>>) param.getValue();
            for (Map<String, Object> line : tableValues) {
                for (String key : line.keySet()) {
                    Object cellValue = line.get(key);
                    if (cellValue instanceof String) { // cell is text so
                        // test data
                        if (ParameterValueUtil.valueContains((String) cellValue, name)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static boolean isUseData(final ElementParameterType param, final String name) {
        if (param == null || name == null) {
            return false;
        }
        if (param.getField().equals(EParameterFieldType.TABLE.getName())) { // for TABLE
            EList elementValue = param.getElementValue();
            if (elementValue != null) {
                for (ElementValueType valueType : (List<ElementValueType>) elementValue) {
                    if (valueType.getValue() != null) { // cell is text so
                        // test data
                        if (ParameterValueUtil.valueContains(valueType.getValue(), name)) {
                            return true;
                        }
                    }
                }
            }
        } else {
            String value = param.getValue();
            if (value != null && ParameterValueUtil.valueContains(value, name)) {
                return true;
            }
        }
        return false;
    }

    public static boolean valueContains(String value, String toTest) {
        if (value.contains(toTest)) {
            Perl5Matcher matcher = new Perl5Matcher();
            Perl5Compiler compiler = new Perl5Compiler();
            Pattern pattern;

            try {
                pattern = compiler.compile(getQuotePattern(toTest));
                if (matcher.contains(value, pattern)) {
                    return true;
                }
            } catch (MalformedPatternException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }

    private static String getQuotePattern(String toTest) {
        final String prefix = "\\b("; //$NON-NLS-1$
        String suffix = ")\\b"; //$NON-NLS-1$
        if (!ContextParameterUtils.isContainContextParam(toTest)) { // context parameter renamed
            suffix = ")(\\b|\\_)"; //$NON-NLS-1$
        }

        return prefix + UpdateContextVariablesHelper.replaceSpecialChar(toTest) + suffix;

    }
}
