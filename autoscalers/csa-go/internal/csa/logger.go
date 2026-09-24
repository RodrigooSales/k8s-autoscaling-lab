package csa

import (
	"fmt"
	"io"
	"os"
	"time"
)

type adapterLogger struct {
	name   string
	file   *os.File
	stderr io.Writer
}

func newAdapterLogger(name, path string, stderr io.Writer) (*adapterLogger, error) {
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return nil, err
	}
	return &adapterLogger{name: name, file: file, stderr: stderr}, nil
}

func (logger *adapterLogger) info(message string) error {
	return logger.writeAs(logger.name, "INFO", message)
}

func (logger *adapterLogger) error(message string) error {
	return logger.writeAs(logger.name, "ERROR", message)
}

func (logger *adapterLogger) fatal(message string) error {
	return logger.writeAs(logger.name, "CRITICAL", message)
}

func (logger *adapterLogger) infoAs(name, message string) error {
	return logger.writeAs(name, "INFO", message)
}

func (logger *adapterLogger) errorAs(name, message string) error {
	return logger.writeAs(name, "ERROR", message)
}

func (logger *adapterLogger) writeAs(name, level, message string) error {
	line := fmt.Sprintf("%s %-12s - %6s - %s\n", time.Now().Format("2006-01-02 15:04:05,000"), name, level, message)
	if _, err := io.WriteString(logger.file, line); err != nil {
		return err
	}
	if _, err := io.WriteString(logger.stderr, line); err != nil {
		return err
	}
	return nil
}

func (logger *adapterLogger) close() error {
	return logger.file.Close()
}
