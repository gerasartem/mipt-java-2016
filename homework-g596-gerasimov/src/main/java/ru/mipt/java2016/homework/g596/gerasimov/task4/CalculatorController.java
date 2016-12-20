package ru.mipt.java2016.homework.g596.gerasimov.task4;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import ru.mipt.java2016.homework.base.task1.Calculator;
import ru.mipt.java2016.homework.base.task1.ParsingException;

@RestController
public class CalculatorController {
    private static final Logger LOG = LoggerFactory.getLogger(CalculatorController.class);
    @Autowired private Calculator calculator;
    @Autowired private BillingDao billingDao;

    @RequestMapping(path = "/ping", method = RequestMethod.GET, produces = "text/plain")
    public String echo() {
        return "OK\n";
    }

    @RequestMapping(path = "/eval", method = RequestMethod.POST, consumes = "text/plain",
            produces = "text/plain")
    public String eval(Authentication authentication, @RequestBody String expression)
            throws ParsingException {
        LOG.debug("Evaluation request: [" + expression + "]");
        double result;
        try {
            expression = reformatExpression(authentication.getName(), expression);
            result = calculator.calculate(expression);
        } catch (ParsingException exception) {
            LOG.trace(exception.getMessage());
            return exception.getMessage() + "\n";
        }
        LOG.trace("Result: " + result);
        return Double.toString(result) + "\n";
    }

    @RequestMapping(path = "/variable/{name}", method = RequestMethod.GET, produces = "text/plain")
    public String getVariable(Authentication authentication, @PathVariable String name) {
        LOG.debug("Get request for variable: " + authentication.getName() + ":" + name);
        return billingDao.getVariable(authentication.getName(), name).getExpression() + "\n";
    }

    @RequestMapping(path = "/variable", method = RequestMethod.GET, produces = "text/plain")
    public String getAllVariable(Authentication authentication) {
        LOG.debug("Get request for ALL variables: " + authentication.getName());
        StringBuilder stringBuilder = new StringBuilder();
        List<BillingVariable> variables = billingDao.getAllVariables(authentication.getName());

        if (variables.size() == 0) {
            return "There are no variables\n";
        }

        for (BillingVariable var : variables) {
            stringBuilder.append(var.getName() + ":" + var.getValue() + ":" + var.getExpression()
                    + "\n");
        }

        LOG.trace("Get request completed");

        return stringBuilder.toString();
    }

    @RequestMapping(path = "/variable/{name}", method = RequestMethod.PUT, consumes = "text/plain",
            produces = "text/plain")
    public String addVariable(Authentication authentication, @PathVariable String name,
            @RequestBody String expression) {
        LOG.debug("Add request for variable: " + authentication.getName() + ":" + name);
        String newExpression = reformatExpression(authentication.getName(), expression);
        BillingVariable variable;

        try {
            variable = new BillingVariable(authentication.getName(), name,
                    calculator.calculate(newExpression), expression);
        } catch (ParsingException exception) {
            LOG.error(exception.getMessage());
            return exception.getMessage() + "\n";
        }

        if (billingDao.addVariable(variable)) {
            LOG.trace("Variable successfully added: " + authentication.getName() + ":" + name);
            return "New variable successfully added\n";
        }

        LOG.trace("Variable already exists: " + authentication.getName() + ":" + name);
        return "Variable already exists\n";
    }

    @RequestMapping(path = "/variable/{name}", method = RequestMethod.DELETE,
            consumes = "text/plain", produces = "text/plain")
    public String deleteVariable(Authentication authentication, @PathVariable String name) {
        LOG.debug("Delete request for variable: " + authentication.getName() + ":" + name);

        if (billingDao.deleteVariable(authentication.getName(), name)) {
            LOG.trace("Variable was successfully deleted: " + authentication.getName() + ":" + name);
            return "Variable successfully deleted\n";
        }

        LOG.trace("Variable does't exists: " + authentication.getName() + ":" + name);
        return "Variable doesn't exist\n";
    }


    @RequestMapping(path = "/variable", method = RequestMethod.DELETE, produces = "text/plain")
    public String deleteAllVariables(Authentication authentication) {
        LOG.debug("Delete request for ALL variables: " + authentication.getName());

        if (billingDao.deleteAllVariables(authentication.getName())) {
            LOG.trace("Variables were successfully deleted: " + authentication.getName());
            return "Variables successfully deleted\n";
        }

        LOG.trace("No variables available: " + authentication.getName());
        return "No variables for user " + authentication.getName() + "\n";
    }

    public String reformatExpression(String username, String expression) {
        expression = expression.replaceAll("\\s", "");
        expression = "(" + expression + ")";
        List<BillingVariable> variables = billingDao.getAllVariables(username);

        for (BillingVariable currentVariable : variables) {
            Pattern pattern =
                    Pattern.compile("([-+*/(),])" + currentVariable.getName() + "([-+*/(),])");
            Matcher matcher = pattern.matcher(expression);
            expression =
                    matcher.replaceAll("$1" + Double.toString(currentVariable.getValue()) + "$2");
        }

        return expression;
    }
}
