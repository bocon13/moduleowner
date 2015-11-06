Gerrit.install(function(self) {

    function addOwnerLabel() {
        if (!self.moduleOwner) {
            return;
        }
        if (self.moduleOwner == "APPROVED") {
            var style = Gerrit.css('color: green;');
            var text = 'You are a module owner';
        } else if (self.moduleOwner == "DENIED") {
            var style = Gerrit.css('color: red;');
            var text = 'You are not a module owner. Please do not +2 or Submit.';
        } else  {
            return;
        }
        var change_plugins = document.getElementById('change_plugins');
        change_plugins.appendChild(
            Gerrit.html('<hr /><span class="{style}">{text}</span>',
                {style: style, text: text}));
    }
    function disableSubmitButton() {
        // This is very brittle because it relies on a long class name:
        //    button class: com-google-gerrit-client-change-Actions_BinderImpl_GenCss_style-submit
        var elements = document.getElementsByClassName("com-google-gerrit-client-change-Actions_BinderImpl_GenCss_style-submit");
        if (elements.length > 0 &&
            self.moduleOwner && self.moduleOwner == "DENIED") {
            console.log("Hiding submit button because not module owner");
            var button = elements[0];
            button.setAttribute("style", "display: none;");
        }
    }
    function onShowChange(c, r) {
        if(!self.getCurrentUser() || self.getCurrentUser()._account_id ==  0) {
            return; // no user logged in
        }
        var url = "changes/"
            + c._number
            + "/revisions/"
            + r._number
            + "/"
            + self.getPluginName()
            + "~"
            + "file-owner";
        Gerrit.get(url, function(r) {
            console.log(r);
            self.moduleOwner = r;
            addOwnerLabel();
            disableSubmitButton();
            //TODO disable +2 if appropriate
        });
    }
    function onSubmitChange(c, r) {
        if (self.moduleOwner && self.moduleOwner == "DENIED") {
            alert("You are not a module owner for this patch set. Submit is disabled.");
            return false;
        }
        return true;
    }
    Gerrit.on('showchange', onShowChange);
    Gerrit.on('submitchange', onSubmitChange);
  });
