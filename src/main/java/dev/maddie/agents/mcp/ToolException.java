package dev.maddie.agents.mcp;

/** A tool failed in a way the AI should hear about (bad arguments, out of reach, ...). */
public class ToolException extends Exception {
    public ToolException(String message) {
        super(message);
    }
}
